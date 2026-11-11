package com.ga.notificationservice;

import com.ga.notificationservice.Event.PriceAlertConsumer;
import com.ga.notificationservice.Event.TransactionEmailConsumer;
import com.ga.notificationservice.Service.EmailService;
import com.ga.notificationservice.Service.NotificationService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriceAlertEmailTests {

    private JavaMailSender javaMailSender;
    private NotificationService notificationService;
    private EmailService emailService;
    private PriceAlertConsumer consumer;
    private MimeMessage message;

    @BeforeEach
    void setUp() {
        javaMailSender = mock(JavaMailSender.class);
        notificationService = mock(NotificationService.class);
        emailService = new EmailService(javaMailSender);
        ReflectionTestUtils.setField(emailService, "fromEmail", "notifications@example.com");
        message = new MimeMessage(Session.getInstance(new Properties()));
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        consumer = new PriceAlertConsumer(notificationService, emailService, JsonMapper.builder().build());
    }

    @Test
    void validPriceAlertSendsNotificationAndEmail() throws Exception {
        consumer.receive("""
                {"emailAddress":"investor@example.com","message":"Price alert triggered: AAPL reached $150 (target: $150)"}
                """);

        verify(notificationService).sendNotification("investor@example.com",
                "Price alert triggered: AAPL reached $150 (target: $150)");
        verify(javaMailSender).send(message);
        assertEquals("investor@example.com", message.getAllRecipients()[0].toString());
        assertEquals("notifications@example.com", message.getFrom()[0].toString());
        assertEquals("Investment Price Alert", message.getSubject());
        assertTrue(message.getContent().toString().contains("AAPL reached $150 (target: $150)"));
        message.saveChanges();
        assertTrue(message.isMimeType("text/html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null", "[]", "{}",
            "{\"emailAddress\":\"investor@example.com\"}",
            "{\"emailAddress\":null,\"message\":\"alert\"}",
            "{\"emailAddress\":\" \",\"message\":\"alert\"}",
            "{\"emailAddress\":42,\"message\":\"alert\"}",
            "{\"emailAddress\":\"investor@example.com\",\"message\":null}",
            "{\"emailAddress\":\"investor@example.com\",\"message\":{}}",
            "{\"emailAddress\":\"investor@example.com\",\"message\":\" \"}"})
    void malformedPriceAlertDoesNotSend(String payload) {
        assertThrows(IllegalStateException.class, () -> consumer.receive(payload));
        verifyNoInteractions(notificationService);
        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void alertTextIsEscapedInHtmlEmail() throws Exception {
        emailService.sendPriceAlertEmail("investor@example.com", "A&B <target>");
        assertTrue(message.getContent().toString().contains("A&amp;B &lt;target&gt;"));
    }

    @Test
    void smtpFailurePropagatesAfterNotification() {
        MailSendException failure = new MailSendException("SMTP unavailable");
        doThrow(failure).when(javaMailSender).send(message);

        assertSame(failure, assertThrows(MailSendException.class, () -> consumer.receive("""
                {"emailAddress":"investor@example.com","message":"Price alert triggered"}
                """)));
        verify(notificationService).sendNotification("investor@example.com", "Price alert triggered");
    }

    @Test
    void preparationFailureUsesMailSendException() throws Exception {
        MimeMessage invalidMessage = mock(MimeMessage.class);
        when(javaMailSender.createMimeMessage()).thenReturn(invalidMessage);
        doThrow(new jakarta.mail.MessagingException("Cannot set subject"))
                .when(invalidMessage).setSubject(anyString(), anyString());

        MailSendException failure = assertThrows(MailSendException.class,
                () -> emailService.sendPriceAlertEmail("investor@example.com", "Alert"));
        assertEquals("Failed to prepare price alert email", failure.getMessage());
        verify(javaMailSender, never()).send(any(MimeMessage.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUY", "SELL"})
    void transactionConsumerPreservesConfirmationEmail(String type) throws Exception {
        TransactionEmailConsumer transactionConsumer = new TransactionEmailConsumer(emailService,
                JsonMapper.builder().build());
        transactionConsumer.receive("""
                {"emailAddress":"investor@example.com","type":"%s","assetSymbol":"AAPL","quantity":"2","totalAmount":"300.00"}
                """.formatted(type));

        verify(javaMailSender).send(message);
        assertEquals("investor@example.com", message.getAllRecipients()[0].toString());
        assertEquals(type.equals("BUY") ? "Investment Purchase Confirmation" : "Investment Sale Confirmation",
                message.getSubject());
        String body = message.getContent().toString();
        assertTrue(body.contains("<strong>Transaction:</strong> " + type));
        assertTrue(body.contains("<strong>Asset:</strong> AAPL"));
        assertTrue(body.contains("<strong>Quantity:</strong> 2 shares"));
        assertTrue(body.contains("<strong>Total Amount:</strong> $300.00"));
        verifyNoInteractions(notificationService);
    }
}
