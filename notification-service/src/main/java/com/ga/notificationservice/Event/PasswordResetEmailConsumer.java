package com.ga.notificationservice.Event;

import com.ga.notificationservice.Service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class PasswordResetEmailConsumer {
    private final EmailService emailService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "auth.password-reset-email")
    public void receive(String message) {
        try {
            //convert JSON into readable data
            JsonNode event = jsonMapper.readTree(message);

            //extract email and token
            String email = event.get("emailAddress").asText();
            String token = event.get("token").asText();

            //send verification email
            emailService.sendPasswordResetEmail(email, token);

        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to read password reset email event", e);
        }
    }
}
