package com.ga.authservice.Service;

import com.ga.authservice.DTO.Request.*;
import com.ga.authservice.DTO.Response.LoginResponse;
import com.ga.authservice.DTO.Response.RegisterResponse;
import com.ga.authservice.Enums.Role;
import com.ga.authservice.Enums.UserStatus;
import com.ga.authservice.Event.*;
import com.ga.authservice.Exception.*;
import com.ga.authservice.Model.EmailVerificationToken;
import com.ga.authservice.Model.OutboxEvent;
import com.ga.authservice.Model.PasswordResetToken;
import com.ga.authservice.Model.User;
import com.ga.authservice.Repository.EmailVerificationTokenRepository;
import com.ga.authservice.Repository.PasswordResetTokenRepository;
import com.ga.authservice.Repository.UserRepository;
import com.ga.authservice.Repository.OutboxEventRepository;
import com.ga.authservice.Security.JWTUtils;
import com.ga.authservice.Security.MyUserDetails;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@EnableScheduling
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final JWTUtils jwtUtils;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final ApplicationEventPublisher eventPublisher;

    private final AuthEventPublisher authEventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper objectMapper;

    @Transactional
    public RegisterResponse register(RegisterRequest request){
        //if email already exists throw an exception
        if(userRepository.existsByEmailAddress(request.getEmailAddress())){
            //add warn log
            log.warn("Registration attempt with existing email: {}", request.getEmailAddress());
            throw new InformationExistException("user with email address " + request.getEmailAddress() + " already exists");
        }

        //registration
        //create user
        User userObject = new User();
        userObject.setFirstName(request.getFirstName());
        userObject.setLastName(request.getLastName());
        userObject.setPhoneNumber(request.getPhoneNumber());
        userObject.setEmailAddress(request.getEmailAddress());
        userObject.setRole(Role.USER);
        userObject.setStatus(UserStatus.UNVERIFIED);

        //encoded password
        userObject.setPassword(passwordEncoder.encode(request.getPassword()));

        //save User
        userRepository.save(userObject);

        //generate unique token
        String token = UUID.randomUUID().toString();

        //create EmailVerificationToken object
        EmailVerificationToken emailVerificationToken = new EmailVerificationToken();

        emailVerificationToken.setToken(token);
        emailVerificationToken.setUser(userObject); //connect it to userObject
        emailVerificationToken.setExpiresAt(LocalDateTime.now().plusHours(1));

        //save it using EmailVerificationTokenRepository
        emailVerificationTokenRepository.save(emailVerificationToken);

        //send verification email request in db with user and verification token
        //create the event
        VerificationEmailEvent event = new VerificationEmailEvent(userObject.getEmailAddress(), token);

        //create outbox record
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventType("VERIFICATION_EMAIL");

        //conver event into JSON
        try {
            outboxEvent.setPayload(objectMapper.writeValueAsString(event));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize verification event", e);
        }

        //save it
        outboxEventRepository.save(outboxEvent);

        //add info log for Successful registration
        log.info("User registered successfully: {}", userObject.getEmailAddress());

        //create RegisterResponse
        RegisterResponse registerResponse = new RegisterResponse("Registration successful, Please verify your email using the link.");

        //return RegisterResponse
        return registerResponse;
    }

    @Transactional
    public void verifyEmail(String token) {
        EmailVerificationToken verificationToken = emailVerificationTokenRepository.findByToken(token)
                .orElseThrow(() -> new InformationNotFoundException("Verification token not found"));

        if(verificationToken.getExpiresAt().isBefore(LocalDateTime.now())){
            throw new TokenExpiredException("Verification token has expired");
        }

        //get the user associated with the token
        User user = verificationToken.getUser();

        //check user status
        if (user.getStatus() != UserStatus.UNVERIFIED){
            throw new InformationExistException("Email address has already been verified");
        }

        //activate user
        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        //save verification event in the same db transaction as the user's activation
        //create the event
        UserVerifiedEvent event = new UserVerifiedEvent(user.getId(), user.getEmailAddress());

        //create outbox record
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventType("USER_VERIFIED");

        //conver event into JSON
        try {
            outboxEvent.setPayload(objectMapper.writeValueAsString(event));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize user verification event", e);
        }

        //save it
        outboxEventRepository.save(outboxEvent);

    }

    public LoginResponse login(LoginRequest request){
        //get user email
        User user = userRepository.findByEmailAddress(request.getEmailAddress())
                .orElseThrow(() -> new InformationNotFoundException("User not found"));


        //compare hashed password and user password input
        if(!passwordEncoder.matches(request.getPassword(), user.getPassword())){
            //add warn log then throw an exception
            log.warn("Failed login attempt for email: {}", request.getEmailAddress());
            throw new InvalidCredentialsException("Invalid email or password");
        }

        //check user status
        if(user.getStatus() == UserStatus.UNVERIFIED){
            log.warn("Login blocked for unverified account: {}", user.getEmailAddress());
            throw new AccountStatusException("Please verify your email before logging in");
        }
        if (user.getStatus() == UserStatus.INACTIVE) {
            log.warn("Login blocked for inactive account: {}", user.getEmailAddress());
            throw new AccountStatusException("Account is inactive");
        }

        //convert the user to MyUserDetails for JWT generation
        MyUserDetails myUserDetails = new MyUserDetails(user);

        //generate jwt token
        String jwtToken = jwtUtils.generateJwtToken(myUserDetails);

        //info log for Successful login
        log.info("User logged in successfully: {}", user.getEmailAddress());

        return new LoginResponse("Login successful", jwtToken);
    }

    public void changePassword(ChangePasswordRequest request, String emailAddress){
        //get authenticated user email
        User user = userRepository.findByEmailAddress(emailAddress)
                .orElseThrow(() -> new InformationNotFoundException("User not found"));

        //compare hashed password and user password input
        if(!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())){
            throw new InvalidCredentialsException("Current password is not correct");
        }

        //set new password
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));

        //save user
        userRepository.save(user);

    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        //get user email
        User user = userRepository.findByEmailAddress(request.getEmailAddress())
                .orElseThrow(() -> new InformationNotFoundException("User not found"));

        //generate reset token
        String token = UUID.randomUUID().toString();

        //create PasswordResetToken object
        PasswordResetToken passwordResetToken = new PasswordResetToken();

        passwordResetToken.setToken(token);
        passwordResetToken.setUser(user); //connect it to user
        passwordResetToken.setExpiresAt(LocalDateTime.now().plusHours(1));

        //save it using passwordResetTokenRepository
        passwordResetTokenRepository.save(passwordResetToken);

        //create the event
        PasswordResetEmailEvent event = new PasswordResetEmailEvent(user.getEmailAddress(), token);

        //create outbox record
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventType("PASSWORD_RESET_EMAIL");

        //conver event into JSON
        try {
            outboxEvent.setPayload(objectMapper.writeValueAsString(event));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize password reset event", e);
        }

        //save it
        outboxEventRepository.save(outboxEvent);
    }

    public void resetPassword(ResetPasswordRequest request) {
        //find token
        PasswordResetToken passwordResetToken = passwordResetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new InformationNotFoundException("Verification token not found"));

        //check expiration
        if(passwordResetToken.getExpiresAt().isBefore(LocalDateTime.now())){
            throw new TokenExpiredException("Reset Password token has expired");
        }

        //get associated user
        User user = passwordResetToken.getUser();

        //encode new password
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));

        //save user
        userRepository.save(user);

        //Invalidate token
        passwordResetTokenRepository.delete(passwordResetToken);

    }

}
