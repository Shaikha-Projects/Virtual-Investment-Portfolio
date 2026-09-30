package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.RegisterRequest;
import com.ga.investmentportfolio.DTO.Response.RegisterResponse;
import com.ga.investmentportfolio.Enums.Role;
import com.ga.investmentportfolio.Enums.UserStatus;
import com.ga.investmentportfolio.Exception.InformationExistException;
import com.ga.investmentportfolio.Model.EmailVerificationToken;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.EmailVerificationTokenRepository;
import com.ga.investmentportfolio.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final EmailService emailService;

    public RegisterResponse register(RegisterRequest request){
        System.out.println("service calling register ==>");

        //if email already exists throw an exception
        if(userRepository.existsByEmailAddress(request.getEmailAddress())){
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

        //create RegisterResponse
        RegisterResponse registerResponse = new RegisterResponse("Registration successful, Please verify your email using the link.");

        //return RegisterResponse
        return registerResponse;


    }

}
