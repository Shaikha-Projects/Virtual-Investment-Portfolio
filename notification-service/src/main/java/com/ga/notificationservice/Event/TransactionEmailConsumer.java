package com.ga.notificationservice.Event;
import com.ga.notificationservice.Service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class TransactionEmailConsumer {
    private final EmailService emailService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "investment.transaction-completed")
    public void receive(String message) {
        try {
            //convert JSON into readable data
            JsonNode event = jsonMapper.readTree(message);

            //extract email, type, and asset symbol
            String email = event.get("emailAddress").asText();
            String type = event.get("type").asText();
            String assetSymbol = event.get("assetSymbol").asText();


            BigDecimal quantity = new BigDecimal(event.get("quantity").asText());
            BigDecimal totalAmount = new BigDecimal(event.get("totalAmount").asText());

            //send email
            emailService.sendTransactionEmail(email, type, assetSymbol, quantity, totalAmount);

        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to read transaction email event", e
            );
        }
    }
}
