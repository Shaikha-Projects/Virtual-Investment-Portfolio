package com.ga.investmentportfolio.Event;

import com.ga.investmentportfolio.Service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class InvestmentNotificationListener {
    //to receive the event and sends the notification

    private final NotificationService notificationService;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNotification(InvestmentNotificationEvent event) {

        try {
            notificationService.sendNotification(
                    event.getEmail(),
                    event.getMessage()
            );
        } catch (Exception e) {
            log.error("Unexpected notification failure - user: {}",
                    event.getEmail(), e);
        }
    }
}
