package com.ga.authservice.Event;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthEventPublisher {

    private final KafkaTemplate<String, VerificationEmailEvent> kafkaTemplate;

    //prepare auth to send verification email request to kafka topic
    public void publishVerificationEmail(VerificationEmailEvent event) {

        kafkaTemplate.send(
                "auth.verification-email",
                event.emailAddress(),
                event
        );

    }
}
