package com.ga.investmentservice.Event;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import com.ga.investmentservice.Service.PortfolioProvisioningService;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;

@Component
@RequiredArgsConstructor
public class UserVerifiedConsumer {
    private final PortfolioProvisioningService portfolioProvisioningService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "auth.user-verified")
    public void receive(String message) {
        try {
            JsonNode event = jsonMapper.readTree(message);

            JsonNode userIdNode = event.get("userId");

            if (userIdNode == null || !userIdNode.isIntegralNumber()) {
                throw new IllegalArgumentException("Invalid user ID in verification event");
            }

            long userId = userIdNode.asLong();

            if (userId <= 0) {
                throw new IllegalArgumentException("User ID must be positive");
            }

            portfolioProvisioningService.createPortfolio(userId);

        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Failed to read user verified event", e);
        }

    }
}
