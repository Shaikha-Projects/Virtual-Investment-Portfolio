package com.ga.investmentportfolio.Config;

import com.ga.investmentportfolio.Enums.Role;
import com.ga.investmentportfolio.Enums.UserStatus;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class AdminDataInitializer implements CommandLineRunner {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.email}")
    private String adminEmail;

    @Value("${admin.password}")
    private String adminPassword;

    @Override
    public void run(String... args) {

        //if admin email does not exist
        if (!userRepository.existsByEmailAddress(adminEmail)){
            //create admin
            User admin = new User();
            admin.setFirstName("Admin");
            admin.setLastName("Investment");
            admin.setPhoneNumber("39993148");
            admin.setEmailAddress(adminEmail);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setRole(Role.ADMIN);
            admin.setStatus(UserStatus.ACTIVE);

            //save admin
            userRepository.save(admin);

        }

    }

}
