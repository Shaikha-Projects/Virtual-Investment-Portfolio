package com.ga.investmentservice.Service;

import com.ga.investmentservice.Event.OutboxPublisher;
import com.ga.investmentservice.Event.WalletCompletedEvent;
import com.ga.investmentservice.Model.OutboxEvent;
import com.ga.investmentservice.Repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletOutboxTest {
    @Test
    void walletEventUsesExistingOutboxWithOnlyCompletionDetails() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        JsonMapper mapper = JsonMapper.builder().build();
        InvestmentEventService service = new InvestmentEventService(repository, mapper);
        UUID reference = UUID.randomUUID();
        service.saveWalletEvent(new WalletCompletedEvent(42L, reference, "DEPOSIT", new BigDecimal("10.00"), "COMPLETED"));
        var saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        assertEquals("WALLET_COMPLETED", saved.getValue().getEventType());
        assertFalse(saved.getValue().isPublished());
        var payload = mapper.readTree(saved.getValue().getPayload());
        assertEquals(5, payload.size());
        assertEquals(42L, payload.get("userId").asLong());
        assertEquals(reference.toString(), payload.get("reference").asText());
        assertEquals("DEPOSIT", payload.get("operationType").asText());
        assertEquals(0, new BigDecimal("10.00").compareTo(new BigDecimal(payload.get("amount").asText())));
        assertEquals("COMPLETED", payload.get("status").asText());
        assertEquals(Propagation.MANDATORY, InvestmentEventService.class.getMethod("saveWalletEvent", WalletCompletedEvent.class)
                .getAnnotation(Transactional.class).propagation());
    }

    @ParameterizedTest
    @CsvSource({"WALLET_COMPLETED,investment.wallet-completed", "TRANSACTION_COMPLETED,investment.transaction-completed",
            "PRICE_ALERT_TRIGGERED,investment.price-alert-triggered", "INVESTMENT_NOTIFICATION,investment.realtime-notification"})
    void publisherRoutesWalletAndExistingEvents(String type, String topic) {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxEvent event = event(type);
        when(repository.findByPublishedFalseOrderByIdAsc()).thenReturn(List.of(event));
        when(kafka.send(topic, "1", "{}")).thenReturn(CompletableFuture.completedFuture(null));
        new OutboxPublisher(repository, kafka).publishPendingEvents();
        verify(kafka).send(topic, "1", "{}");
        assertTrue(event.isPublished());
        verify(repository).save(event);
    }

    @Test
    void failedKafkaSendLeavesEventUnpublishedForRetry() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxEvent event = event("WALLET_COMPLETED");
        when(repository.findByPublishedFalseOrderByIdAsc()).thenReturn(List.of(event));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Unavailable")));
        assertThrows(IllegalStateException.class, () -> new OutboxPublisher(repository, kafka).publishPendingEvents());
        assertFalse(event.isPublished());
        verify(repository, never()).save(any());
    }

    private OutboxEvent event(String type) {
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setEventType(type);
        event.setPayload("{}");
        return event;
    }
}
