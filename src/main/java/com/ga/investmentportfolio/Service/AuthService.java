package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.RegisterRequest;
import com.ga.investmentportfolio.DTO.Response.RegisterResponse;
import com.ga.investmentportfolio.Enums.Role;
import com.ga.investmentportfolio.Enums.UserStatus;
import com.ga.investmentportfolio.Exception.InformationExistException;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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

        //create RegisterResponse
        RegisterResponse registerResponse = new RegisterResponse("Registration successful, Please verify your email using the link.");

        //return RegisterResponse
        return registerResponse;


    }

}
