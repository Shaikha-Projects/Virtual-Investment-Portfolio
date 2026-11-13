package com.ga.investmentservice.Service;

import com.ga.investmentservice.Event.InvestmentNotificationEvent;
import com.ga.investmentservice.Event.PriceAlertTriggeredEvent;
import com.ga.investmentservice.Event.TransactionCompletedEvent;
import com.ga.investmentservice.Event.WalletCompletedEvent;
import com.ga.investmentservice.Model.OutboxEvent;
import com.ga.investmentservice.Repository.OutboxEventRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class InvestmentEventService {
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveWalletEvent(WalletCompletedEvent event) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventType("WALLET_COMPLETED");
        outboxEvent.setPayload(jsonMapper.writeValueAsString(event));
        outboxEventRepository.save(outboxEvent);
    }

    //prepare and save a transaction event
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveTransactionEvent(TransactionCompletedEvent event) {
        //create a new outbox event
        OutboxEvent outboxEvent = new OutboxEvent();

        //set its type
        outboxEvent.setEventType("TRANSACTION_COMPLETED");

        //convert transaction details into JSON
        outboxEvent.setPayload(jsonMapper.writeValueAsString(event));

        //save it
        outboxEventRepository.save(outboxEvent);

    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void savePriceAlertEvent(PriceAlertTriggeredEvent event) {
        //create a new outbox event
        OutboxEvent outboxEvent = new OutboxEvent();

        //set its type
        outboxEvent.setEventType("PRICE_ALERT_TRIGGERED");

        //convert transaction details into JSON
        outboxEvent.setPayload(jsonMapper.writeValueAsString(event));

        //save it
        outboxEventRepository.save(outboxEvent);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveInvestmentNotificationEvent(InvestmentNotificationEvent event) {
        //create a new outbox event
        OutboxEvent outboxEvent = new OutboxEvent();

        //set its type
        outboxEvent.setEventType("INVESTMENT_NOTIFICATION");

        //convert transaction details into JSON
        outboxEvent.setPayload(jsonMapper.writeValueAsString(event));

        //save it
        outboxEventRepository.save(outboxEvent);
    }
}
