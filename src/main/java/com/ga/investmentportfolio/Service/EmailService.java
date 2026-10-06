package com.ga.investmentportfolio.Service;

import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender javaMailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public void sendVerificationEmail(String emailAddress, String token){
        //instantiate the SimpleMailMessage object
        SimpleMailMessage message = new SimpleMailMessage();

        //populate email fields
        message.setFrom(fromEmail);
        message.setTo(emailAddress);
        message.setSubject("Verify your email");

        //construct url string
        String verificationURL = "http://localhost:8081/auth/verify?token=" + token;

        //format email body
        String emailBody = "Thank you for registering in Investment Portfolio! \n\n" +
                "Please click the link below to verify your email address:\n" +
                verificationURL + "\n\n" +
                "If you did not request this, please ignore this email.";


        message.setText(emailBody);

        // Send the message using JavaMailSender
        javaMailSender.send(message);
    }

    public void sendPasswordResetEmail(String emailAddress, String token){
        //instantiate the SimpleMailMessage object
        SimpleMailMessage message = new SimpleMailMessage();

        //populate email fields
        message.setFrom(fromEmail);
        message.setTo(emailAddress);
        message.setSubject("Reset your Investment Portfolio password");

        //construct url string
        String resetURL = "http://localhost:5173/?token=" + token;

        //format email body
        String emailBody = "Reset password request for Investment Portfolio! \n\n" +
                "Use the link below to reset your password:\n" +
                resetURL + "\n\n" +
                "This link will expire in one hour.\n\n" +
                "If you did not request this, please ignore this email.";


        message.setText(emailBody);

        // Send the message using JavaMailSender
        javaMailSender.send(message);
    }
}
