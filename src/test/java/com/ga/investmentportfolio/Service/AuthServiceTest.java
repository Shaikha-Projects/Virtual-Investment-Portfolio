package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.LoginRequest;
import com.ga.investmentportfolio.Enums.UserStatus;
import com.ga.investmentportfolio.Exception.AccountStatusException;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.*;
import com.ga.investmentportfolio.Security.JWTUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AuthServiceTest {
    //dependencies
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private EmailVerificationTokenRepository emailVerificationTokenRepository;
    private EmailService emailService;
    private PortfolioRepository portfolioRepository;
    private JWTUtils jwtUtils;
    private PasswordResetTokenRepository passwordResetTokenRepository;


    //asset service
    private AuthService authService;

    @BeforeEach
    public void setUp() {

        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailVerificationTokenRepository = mock(EmailVerificationTokenRepository.class);
        emailService = mock(EmailService.class);
        portfolioRepository = mock(PortfolioRepository.class);
        jwtUtils = mock(JWTUtils.class);
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);

        authService = new AuthService(
                userRepository, passwordEncoder, emailVerificationTokenRepository,
                emailService, portfolioRepository, jwtUtils,
                passwordResetTokenRepository);
    }

    //test 3: inactive user cannot log in
    @Test
    @DisplayName("When user is inactive then login is rejected")
    public void whenUserIsInactiveThenLoginIsRejected() {
        //create inactive user
        User user = new User();
        user.setEmailAddress("user@test.com");
        user.setPassword("hashedPassword");
        user.setStatus(UserStatus.INACTIVE);

        //create login request
        LoginRequest request = mock(LoginRequest.class);
        when(request.getEmailAddress()).thenReturn("user@test.com");
        when(request.getPassword()).thenReturn("password123");

        //mock repository response
        when(userRepository.findByEmailAddress("user@test.com"))
                .thenReturn(Optional.of(user));

        //password is correct
        when(passwordEncoder.matches("password123", "hashedPassword")).thenReturn(true);

        //verify inactive account is rejected
        assertThrows(AccountStatusException.class, () -> authService.login(request));

    }

}
