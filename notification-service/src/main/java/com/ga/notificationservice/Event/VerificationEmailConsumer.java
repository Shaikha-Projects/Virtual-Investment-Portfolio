package com.ga.notificationservice.Event;

import lombok.RequiredArgsConstructor;import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ga.notificationservice.Service.EmailService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;

@Component
@RequiredArgsConstructor
public class VerificationEmailConsumer {

    private final EmailService emailService;
    private final JsonMapper jsonMapper;

    //listen for kafka messages
    @KafkaListener(topics = "auth.verification-email")
    public void receive(String message) {
        try {
            //convert JSON into readable data
            JsonNode event = jsonMapper.readTree(message);

            //extract email and token
            String email = event.get("emailAddress").asText();
            String token = event.get("token").asText();

            //send verification email
            emailService.sendVerificationEmail(email, token);

        } catch (JacksonException e) {
            //handle invalid JSON
            throw new IllegalStateException("Failed to read verification email event", e);
        }
    }
}
