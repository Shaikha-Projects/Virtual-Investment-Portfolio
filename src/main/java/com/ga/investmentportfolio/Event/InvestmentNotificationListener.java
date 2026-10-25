package com.ga.investmentportfolio.Event;

import com.ga.investmentportfolio.Service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class InvestmentNotificationListener {
    //to receive the event and sends the notification

    private final NotificationService notificationService;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNotification(InvestmentNotificationEvent event) {

        notificationService.sendNotification(
                event.getEmail(),
                event.getMessage()
        );
    }
}
