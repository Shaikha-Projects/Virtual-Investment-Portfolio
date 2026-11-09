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
public class PriceAlertConsumer {
    private final NotificationService notificationService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "investment.price-alert-triggered")
    public void receive(String message) {
        try {
            //convert JSON into readable data
            JsonNode event = jsonMapper.readTree(message);

            //extract email and message
            String email = event.get("emailAddress").asText();
            String alertMessage = event.get("message").asText();

            //send email
            notificationService.sendNotification(email, alertMessage);

        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to read price alert event", e);
        }
    }

}
