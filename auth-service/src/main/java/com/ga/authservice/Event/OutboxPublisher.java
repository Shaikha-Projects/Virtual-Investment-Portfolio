package com.ga.authservice.Event;

import com.ga.authservice.Model.OutboxEvent;
import com.ga.authservice.Repository.OutboxEventRepository;
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
    private final KafkaTemplate<String, String> kafkaTemplate; //used to  send messages to Kafka

    //method to read pending events
    @Scheduled(fixedDelay = 5000)
    public void publishPendingEvents() {

        //retrieve all unpublished events
        List<OutboxEvent> events = outboxEventRepository.findByPublishedFalseOrderByIdAsc();

        //loop through events
        for (OutboxEvent event : events) {
            //prep to send event to kafka

            //select Kafka topic based on event type
            String topic = switch (event.getEventType()) {
                case "VERIFICATION_EMAIL" -> "auth.verification-email";
                case "PASSWORD_RESET_EMAIL" -> "auth.password-reset-email";
                case "USER_VERIFIED" -> "auth.user-verified";
                case "WELCOME_EMAIL" -> "auth.welcome-email";
                default -> throw new IllegalArgumentException(
                        "Unknown event type: " + event.getEventType());
            };

            try {
                //publish stored JSON payload to topic
                kafkaTemplate.send(topic, event.getId().toString(), event.getPayload()).get(10, TimeUnit.SECONDS);

                //mark it delivered to Kafka
                event.setPublished(true);
                outboxEventRepository.save(event);

            } catch (Exception e) {
                throw new IllegalStateException("Failed to publish outbox event " + event.getId(), e);
            }

        }
    }
}
