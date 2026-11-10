package com.ga.notificationservice.Event;
import com.ga.notificationservice.Service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class InvestmentNotificationConsumer {
    private final NotificationService notificationService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "investment.realtime-notification")
    public void receive(String message) {
        try {
            JsonNode event = jsonMapper.readTree(message);

            String email = event.get("emailAddress").asText();
            String notificationMessage = event.get("message").asText();

            //send message
            notificationService.sendNotification(email, notificationMessage);

        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to read investment notification event", e);
        }
    }
}
