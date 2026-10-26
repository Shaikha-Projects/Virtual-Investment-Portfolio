package com.ga.investmentportfolio.Service;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender javaMailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public void sendVerificationEmail(String emailAddress, String token) {
        try{

            //instantiate the MimeMessage object to send HTML emails
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");

            //populate email fields
            helper.setFrom(fromEmail);
            helper.setTo(emailAddress);
            helper.setSubject("Verify Your Email - Investment Portfolio");

            //construct url string
            String verificationURL = "http://localhost:8081/auth/verify?token=" + token;

            //format email body
            String emailBody = """
        <html>
        <body style="font-family: Arial, sans-serif; background-color: #f4f6f8; padding: 30px;">

            <div style="background-color: white; padding: 30px;
                        border-radius: 10px; max-width: 500px; margin: auto;">

                <h2 style="color: #1e3a5f;">
                    Virtual Investment Portfolio
                </h2>

                <h3>Welcome!</h3>

                <p>Thank you for registering with us.</p>

                <p>Please verify your email address to activate your account.</p>

                <a href="%s"
                   style="display: inline-block; background-color: #1e3a5f;
                          color: white; padding: 12px 24px;
                          text-decoration: none; border-radius: 6px;">
                    Verify Email
                </a>

                <p style="margin-top: 25px; color: #777; font-size: 12px;">
                    If you did not create this account, please ignore this email.
                </p>

                <hr>

                <p style="font-size: 11px; color: #999;">
                    Virtual Investment Portfolio System<br>
                    Investment simulation only. No real money is involved.
                </p>

            </div>

        </body>
        </html>
        """.formatted(verificationURL);

            //set HTML email body
            helper.setText(emailBody, true);

            // Send the message using JavaMailSender
            javaMailSender.send(message);
        } catch (MessagingException e) {
            throw new MailSendException("Failed to prepare verification email", e);
        }
    }

    public void sendPasswordResetEmail(String emailAddress, String token){
        try{
            //instantiate the MimeMessage object to send HTML emails
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");

            //populate email fields
            helper.setFrom(fromEmail);
            helper.setTo(emailAddress);
            helper.setSubject("Reset Your Password - Investment Portfolio");

            //construct url string
            String resetURL = "http://localhost:5173/?token=" + token;

            //format email body
            String emailBody = """
        <html>
        <body style="font-family: Arial, sans-serif; background-color: #f4f6f8; padding: 30px;">

            <div style="background-color: white; padding: 30px;
                        border-radius: 10px; max-width: 500px; margin: auto;">

                <h2 style="color: #1e3a5f;">
                    Virtual Investment Portfolio
                </h2>

                <h3>Reset Your Password</h3>

                <p>We received a request to reset your account password.</p>

                <p>Click the button below to create a new password.</p>

                <a href="%s"
                   style="display: inline-block; background-color: #1e3a5f;
                          color: white; padding: 12px 24px;
                          text-decoration: none; border-radius: 6px;">
                    Reset Password
                </a>

                <p style="margin-top: 25px;">
                    This password reset link will expire in one hour.
                </p>

                <p style="color: #777; font-size: 12px;">
                    If you did not request a password reset, please ignore this email.
                    Your password will remain unchanged.
                </p>

                <hr>

                <p style="font-size: 11px; color: #999;">
                    Virtual Investment Portfolio System<br>
                    Investment simulation only. No real money is involved.
                </p>

            </div>

        </body>
        </html>
        """.formatted(resetURL);

            //set HTML email body
            helper.setText(emailBody, true);

            //send password reset email
            javaMailSender.send(message);

        } catch (MessagingException e) {
            throw new MailSendException("Failed to prepare verification email", e);
        }

    }
}
