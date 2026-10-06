package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.*;
import com.ga.investmentportfolio.DTO.Response.LoginResponse;
import com.ga.investmentportfolio.DTO.Response.RegisterResponse;
import com.ga.investmentportfolio.Enums.Role;
import com.ga.investmentportfolio.Enums.UserStatus;
import com.ga.investmentportfolio.Exception.*;
import com.ga.investmentportfolio.Model.EmailVerificationToken;
import com.ga.investmentportfolio.Model.PasswordResetToken;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.EmailVerificationTokenRepository;
import com.ga.investmentportfolio.Repository.PasswordResetTokenRepository;
import com.ga.investmentportfolio.Repository.PortfolioRepository;
import com.ga.investmentportfolio.Repository.UserRepository;
import com.ga.investmentportfolio.Security.JWTUtils;
import com.ga.investmentportfolio.Security.MyUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final EmailService emailService;
    private final PortfolioRepository portfolioRepository;
    private final JWTUtils jwtUtils;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private static final Logger log = LoggerFactory.getLogger(AuthService.class); //to write logs

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

        //send verification email
        emailService.sendVerificationEmail(userObject.getEmailAddress(), token);

        //add info log for Successful registration
        log.info("User registered successfully: {}", userObject.getEmailAddress());

        //create RegisterResponse
        RegisterResponse registerResponse = new RegisterResponse("Registration successful, Please verify your email using the link.");

        //return RegisterResponse
        return registerResponse;
    }

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

        //create user portfolio
        Portfolio portfolio = new Portfolio();
        portfolio.setUser(user);
        portfolio.setCashBalance(new BigDecimal("100000.00"));
        portfolioRepository.save(portfolio);

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

        //send reset email
        emailService.sendPasswordResetEmail(user.getEmailAddress(), token);
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
