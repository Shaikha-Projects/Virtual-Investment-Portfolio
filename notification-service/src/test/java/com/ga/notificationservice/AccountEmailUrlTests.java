package com.ga.notificationservice;

import com.ga.notificationservice.Service.EmailService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountEmailUrlTests {
    private static final String TOKEN = "11111111-1111-4111-8111-111111111111";
    private static final String PUBLIC_ORIGIN = "https://virtualinvestmentportfolio.duckdns.org";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                // Test defaults independently of a developer's real environment/credentials.
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                new ConfigDataApplicationContextInitializer().initialize(context);
            })
            .withPropertyValues("spring.mail.username=notifications@example.com")
            .withBean(JavaMailSender.class, () -> {
                JavaMailSender sender = mock(JavaMailSender.class);
                when(sender.createMimeMessage()).thenAnswer(invocation ->
                        new MimeMessage(Session.getInstance(new Properties())));
                return sender;
            })
            .withBean(EmailService.class);

    @Test
    void defaultConfigurationPreservesLocalVerificationAndResetLinks() {
        assertEmailLinks(runner, "http://localhost:8084/auth/verify", "http://localhost:5173/");
    }

    @Test
    void environmentConfigurationUsesPublicNginxVerificationAndFrontendResetRoutes() {
        assertEmailLinks(runner.withPropertyValues(
                "EMAIL_VERIFICATION_URL=" + PUBLIC_ORIGIN + "/api/auth/auth/verify",
                "EMAIL_PASSWORD_RESET_URL=" + PUBLIC_ORIGIN + "/"),
                PUBLIC_ORIGIN + "/api/auth/auth/verify", PUBLIC_ORIGIN + "/");
    }

    private void assertEmailLinks(ApplicationContextRunner contextRunner, String verification, String reset) {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            EmailService service = context.getBean(EmailService.class);
            service.sendVerificationEmail("investor@example.com", TOKEN);
            service.sendPasswordResetEmail("investor@example.com", TOKEN);

            ArgumentCaptor<MimeMessage> messages = ArgumentCaptor.forClass(MimeMessage.class);
            verify(context.getBean(JavaMailSender.class), times(2)).send(messages.capture());
            assertTrue(messages.getAllValues().get(0).getContent().toString()
                    .contains("href=\"" + verification + "?token=" + TOKEN + "\""));
            assertTrue(messages.getAllValues().get(1).getContent().toString()
                    .contains("href=\"" + reset + "?token=" + TOKEN + "\""));
            assertEquals("Verify Your Email - Investment Portfolio", messages.getAllValues().get(0).getSubject());
            assertEquals("Reset Your Password - Investment Portfolio", messages.getAllValues().get(1).getSubject());
        });
    }
}
