package com.ga.investmentservice.Event;

import com.ga.investmentservice.Model.OutboxEvent;
import com.ga.investmentservice.Repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class OutboxPublisher {
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findByPublishedFalseOrderByIdAsc();

        for (OutboxEvent event : events) {

            String topic = switch (event.getEventType()) {
                case "TRANSACTION_COMPLETED" -> "investment.transaction-completed";
                case "PRICE_ALERT_TRIGGERED" -> "investment.price-alert-triggered";
                case "INVESTMENT_NOTIFICATION" -> "investment.realtime-notification";
                case "WALLET_COMPLETED" -> "investment.wallet-completed";
                default -> throw new IllegalArgumentException("Unknown event type");
            };

            try {
                kafkaTemplate.send(topic, event.getId().toString(), event.getPayload()).get(10, TimeUnit.SECONDS);
                event.setPublished(true);
                outboxEventRepository.save(event);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to publish investment event", e);
            }
        }

    }
}
