package com.ga.notificationservice.Event;
import com.ga.notificationservice.Service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;

@Component
@RequiredArgsConstructor
public class WelcomeEmailConsumer {
    private final EmailService emailService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "auth.welcome-email")
    public void receive(String message) {
        try {
            //convert JSON into readable data
            JsonNode event = jsonMapper.readTree(message);

            //extract email
            String email = event.get("emailAddress").asText();

            //send email
            emailService.sendWelcomeEmail(email);

        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to read welcome email event", e);
        }
    }

}
