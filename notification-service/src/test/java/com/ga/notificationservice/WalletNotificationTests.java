package com.ga.notificationservice;

import com.ga.notificationservice.Event.WalletCompletedConsumer;
import com.ga.notificationservice.Service.EmailService;
import com.ga.notificationservice.Service.NotificationService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WalletNotificationTests {
    private final UUID reference = UUID.randomUUID();
    private JavaMailSender sender;
    private NotificationService notifications;
    private JdbcTemplate jdbc;
    private WalletCompletedConsumer consumer;
    private MimeMessage email;

    @BeforeEach
    void setup() {
        sender = mock(JavaMailSender.class);
        notifications = mock(NotificationService.class);
        jdbc = mock(JdbcTemplate.class);
        EmailService emails = new EmailService(sender);
        ReflectionTestUtils.setField(emails, "fromEmail", "notifications@example.com");
        email = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(email);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(42L))).thenReturn("investor@example.com");
        consumer = new WalletCompletedConsumer(notifications, emails, JsonMapper.builder().build(), jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPOSIT", "WITHDRAWAL"})
    void completedWalletEventSendsOneEmailAndNotification(String type) throws Exception {
        consumer.receive(payload(type, "COMPLETED"));
        consumer.receive(payload(type, "COMPLETED"));
        verify(notifications, times(1)).sendNotification(eq("investor@example.com"), contains("USD 10.00"));
        verify(sender, times(1)).send(email);
        assertEquals("investor@example.com", email.getAllRecipients()[0].toString());
        assertEquals("notifications@example.com", email.getFrom()[0].toString());
        assertEquals(type.equals("DEPOSIT") ? "Cash Wallet Deposit Confirmation" : "Cash Wallet Withdrawal Confirmation", email.getSubject());
        assertTrue(email.getContent().toString().contains("USD 10.00"));
        assertTrue(email.getContent().toString().contains(reference.toString()));
        assertTrue(email.getContent().toString().contains("No real money"));
        email.saveChanges();
        assertTrue(email.isMimeType("text/html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED", "CANCELLED"})
    void incompleteOperationsDoNotSend(String status) {
        consumer.receive(payload("DEPOSIT", status));
        verifyNoInteractions(notifications, sender, jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{}", "reference", "amount", "user", "type", "precision"})
    void malformedEventsDoNotSend(String changed) {
        String message = switch (changed) {
            case "reference" -> payload("DEPOSIT", "COMPLETED").replace(reference.toString(), "invalid");
            case "amount" -> payload("DEPOSIT", "COMPLETED").replace("10.00", "-1");
            case "user" -> payload("DEPOSIT", "COMPLETED").replace("42", "0");
            case "type" -> payload("BUY", "COMPLETED");
            case "precision" -> payload("DEPOSIT", "COMPLETED").replace("10.00", "0.001");
            default -> changed;
        };
        assertThrows(IllegalStateException.class, () -> consumer.receive(message));
        verifyNoInteractions(notifications, sender, jdbc);
    }

    @Test
    void smtpRetryDoesNotRepeatTheSseNotification() {
        doThrow(new MailSendException("SMTP unavailable")).doNothing().when(sender).send(email);
        assertThrows(MailSendException.class, () -> consumer.receive(payload("DEPOSIT", "COMPLETED")));
        consumer.receive(payload("DEPOSIT", "COMPLETED"));
        consumer.receive(payload("DEPOSIT", "COMPLETED"));
        verify(notifications, times(1)).sendNotification(anyString(), anyString());
        verify(sender, times(2)).send(email);
    }

    @Test
    void concurrentRedeliveryDoesNotSendTwice() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> consumer.receive(payload("WITHDRAWAL", "COMPLETED")));
            Future<?> second = executor.submit(() -> consumer.receive(payload("WITHDRAWAL", "COMPLETED")));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            verify(sender, times(1)).send(email);
            verify(notifications, times(1)).sendNotification(anyString(), anyString());
        } finally { executor.shutdownNow(); }
    }

    @Test
    void conflictingDuplicateIsRejected() {
        consumer.receive(payload("DEPOSIT", "COMPLETED"));
        assertThrows(IllegalStateException.class, () -> consumer.receive(payload("WITHDRAWAL", "COMPLETED")));
        verify(sender, times(1)).send(email);
        verify(notifications, times(1)).sendNotification(anyString(), anyString());
    }

    @Test
    void missingRecipientDoesNotSend() {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(42L))).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> consumer.receive(payload("DEPOSIT", "COMPLETED")));
        verifyNoInteractions(notifications, sender);
    }

    private String payload(String type, String status) {
        return """
                {"userId":42,"reference":"%s","operationType":"%s","amount":10.00,"status":"%s"}
                """.formatted(reference, type, status);
    }
}
