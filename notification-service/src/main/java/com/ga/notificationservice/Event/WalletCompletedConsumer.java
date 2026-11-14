package com.ga.notificationservice.Event;

import com.ga.notificationservice.Service.EmailService;
import com.ga.notificationservice.Service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WalletCompletedConsumer {
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final JsonMapper jsonMapper;
    private final JdbcTemplate jdbc;
    private final Map<UUID, Delivery> deliveries = new LinkedHashMap<>();

    @KafkaListener(topics = "investment.wallet-completed")
    public synchronized void receive(String message) {
        try {
            JsonNode event = jsonMapper.readTree(message);
            if (event == null || !event.path("status").isString()) {
                throw new IllegalStateException("Invalid wallet event");
            }
            if (!"COMPLETED".equals(event.path("status").asText())) return;
            if (!event.path("userId").isNumber() || !event.path("reference").isString()
                    || !event.path("operationType").isString() || !event.path("amount").isNumber()) {
                throw new IllegalStateException("Invalid wallet event");
            }
            long userId = Long.parseLong(event.path("userId").asText());
            UUID reference = UUID.fromString(event.path("reference").asText());
            String type = event.path("operationType").asText();
            BigDecimal amount = new BigDecimal(event.path("amount").asText());
            if (userId <= 0 || (!"DEPOSIT".equals(type) && !"WITHDRAWAL".equals(type))
                    || amount.signum() <= 0 || amount.scale() > 2
                    || amount.compareTo(new BigDecimal("9999999999999.99")) > 0) {
                throw new IllegalStateException("Invalid wallet event");
            }
            amount = amount.setScale(2);
            String details = userId + ":" + type + ":" + amount.toPlainString();
            Delivery delivery = deliveries.get(reference);
            if (delivery != null && !details.equals(delivery.details)) {
                throw new IllegalStateException("Conflicting wallet event");
            }
            if (delivery != null && delivery.notificationSent && delivery.emailSent) return;

            //use the shared database lookup already used by notification security
            String email = jdbc.queryForObject("select email_address from users where id = ?", String.class, userId);
            if (email == null || email.isBlank()) throw new IllegalStateException("Wallet recipient does not exist");
            if (delivery == null) {
                //keep recent delivery progress without an unbounded cache
                if (deliveries.size() >= 10000) deliveries.remove(deliveries.keySet().iterator().next());
                delivery = new Delivery(details);
                deliveries.put(reference, delivery);
            }
            if (!delivery.notificationSent) {
                String notification = "Wallet " + (type.equals("DEPOSIT") ? "deposit" : "withdrawal")
                        + " completed: USD " + amount.toPlainString() + ". Reference: " + reference;
                notificationService.sendNotification(email, notification);
                delivery.notificationSent = true;
            }
            if (!delivery.emailSent) {
                emailService.sendWalletEmail(email, type, amount, reference);
                delivery.emailSent = true;
            }
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid wallet event");
        }
    }

    @RequiredArgsConstructor
    private static class Delivery {
        private final String details;
        private boolean notificationSent;
        private boolean emailSent;
    }
}
