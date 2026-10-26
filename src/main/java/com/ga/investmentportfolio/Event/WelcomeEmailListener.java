package com.ga.investmentportfolio.Event;

import com.ga.investmentportfolio.Service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class WelcomeEmailListener {
    private final EmailService emailService;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWelcomeEmail(WelcomeEmailEvent event) {

        try {
            emailService.sendWelcomeEmail(event.getEmailAddress());

        } catch (Exception e) {
            log.error("Failed to send welcome email - user: {}",
                    event.getEmailAddress(), e);
        }
    }
}
