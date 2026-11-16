package com.ga.authservice.Config;

import com.ga.authservice.Enums.Role;
import com.ga.authservice.Enums.UserStatus;
import com.ga.authservice.Model.User;
import com.ga.authservice.Repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminDataInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminDataInitializer.class);
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminDataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                @Value("${ADMIN_EMAIL:}") String adminEmail,
                                @Value("${ADMIN_PASSWORD:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.info("Administrator initialization skipped: ADMIN_EMAIL or ADMIN_PASSWORD is missing or blank.");
            return;
        }

        String email = adminEmail.trim();
        if (userRepository.existsByEmailAddress(email)) {
            log.info("Administrator initialization skipped: configured email already exists; account left unchanged.");
            return;
        }

        User admin = new User();
        admin.setFirstName("System");
        admin.setLastName("Administrator");
        // Bootstrap placeholder satisfying the required eight-digit phone field; editable through Profile.
        admin.setPhoneNumber("00000000");
        admin.setEmailAddress(email);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setRole(Role.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        // Repository save commits the insert; Hibernate supplies both timestamp fields.
        userRepository.save(admin);
        log.info("Administrator account initialized successfully.");
    }
}
