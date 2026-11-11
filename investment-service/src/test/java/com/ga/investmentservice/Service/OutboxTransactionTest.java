package com.ga.investmentservice.Service;

import com.ga.investmentservice.Enums.*;
import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.DTO.Response.UserIdentityResponse;
import com.ga.investmentservice.Event.TransactionCompletedEvent;
import com.ga.investmentservice.Model.*;
import com.ga.investmentservice.Repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxTransactionTest {
    private AnnotationConfigApplicationContext context;
    private final List<String> committed = new ArrayList<>();

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean PlatformTransactionManager transactionManager() { return new TestTransactionManager(); }
        @Bean jakarta.persistence.EntityManagerFactory entityManagerFactory() {
            var factory = mock(jakarta.persistence.EntityManagerFactory.class);
            when(factory.createEntityManager()).thenReturn(mock(EntityManager.class));
            when(factory.getProperties()).thenReturn(Map.of());
            return factory;
        }
        @Bean InvestmentOrderRepository orders() { return mock(InvestmentOrderRepository.class); }
        @Bean PortfolioRepository portfolios() { return mock(PortfolioRepository.class); }
        @Bean HoldingRepository holdings() { return mock(HoldingRepository.class); }
        @Bean AssetRepository assets() { return mock(AssetRepository.class); }
        @Bean OutboxEventRepository outbox() { return mock(OutboxEventRepository.class); }
        @Bean AuditLogRepository audits() { return mock(AuditLogRepository.class); }
        @Bean TradeExecutionService trades() { return mock(TradeExecutionService.class); }
        @Bean AuthServiceClient authServiceClient() { return mock(AuthServiceClient.class); }
        @Bean InvestmentEventService events(OutboxEventRepository outbox) {
            return new InvestmentEventService(outbox, JsonMapper.builder().build());
        }
        @Bean AuditLogService audit(AuditLogRepository audits) {
            return new AuditLogService(audits);
        }
        @Bean OrderExecutionService execution(InvestmentOrderRepository orders, PortfolioRepository portfolios,
                HoldingRepository holdings, TradeExecutionService trades, InvestmentEventService events,
                AuditLogService audit, AssetRepository assets, AuthServiceClient authServiceClient) {
            OrderExecutionService service = new OrderExecutionService(orders, portfolios, holdings, trades, events, audit, assets, authServiceClient);
            ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
            return service;
        }
    }

    //No JDBC connection: exercise Spring's real transaction interception and synchronization.
    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        static class State implements SmartTransactionObject {
            boolean active;
            boolean rollbackOnly;
            @Override public boolean isRollbackOnly() { return rollbackOnly; }
            @Override public void flush() { }
        }
        private final ThreadLocal<State> state = ThreadLocal.withInitial(State::new);
        @Override protected Object doGetTransaction() { return state.get(); }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((State) transaction).active; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { ((State) transaction).active = true; }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((State) status.getTransaction()).rollbackOnly = true;
        }
        @Override protected void doCleanupAfterCompletion(Object transaction) { state.remove(); }
    }

    private void recordOnCommit(String value) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { committed.add(value); }
        });
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        Portfolio portfolio = new Portfolio();
        portfolio.setId(5L);
        portfolio.setUserId(42L);
        portfolio.setCashBalance(new BigDecimal("1000.00"));
        portfolio.setReservedCash(new BigDecimal("20.00"));
        Asset asset = new Asset();
        asset.setId(1L);
        asset.setSymbol("ABC");
        asset.setCurrentPrice(new BigDecimal("10.00"));
        InvestmentOrder order = new InvestmentOrder();
        order.setId(7L);
        order.setPortfolio(portfolio);
        order.setAsset(asset);
        order.setOrderType(TransactionType.BUY);
        order.setQuantity(BigDecimal.ONE);
        order.setLimitPrice(new BigDecimal("10.00"));
        order.setStatus(OrderStatus.PENDING);
        when(context.getBean(InvestmentOrderRepository.class).findByIdForUpdate(7L)).thenReturn(Optional.of(order));
        when(context.getBean(InvestmentOrderRepository.class).findOrderOwnerId(7L)).thenReturn(Optional.of(42L));
        when(context.getBean(PortfolioRepository.class).findByIdForUpdate(5L)).thenReturn(Optional.of(portfolio));
        when(context.getBean(AuthServiceClient.class).getUserById(42L)).thenReturn(
                new UserIdentityResponse(42L, "owner@example.com", "USER", "ACTIVE"));
        when(context.getBean(AssetRepository.class).findByIdForUpdate(1L)).thenReturn(Optional.of(asset));
        when(context.getBean(AuditLogRepository.class).save(any(AuditLog.class))).thenAnswer(call -> {
            recordOnCommit("AUDIT");
            return call.getArgument(0);
        });
        when(context.getBean(OutboxEventRepository.class).save(any(OutboxEvent.class))).thenAnswer(call -> {
            OutboxEvent event = call.getArgument(0);
            recordOnCommit(event.getEventType());
            return event;
        });
    }

    @AfterEach void close() { context.close(); }

    @Test
    void backgroundAuditAndBothOutboxEventsJoinTheFinancialTransaction() {
        context.getBean(OrderExecutionService.class).executeSingleOrder(7L);
        assertEquals(List.of("AUDIT", "INVESTMENT_NOTIFICATION", "TRANSACTION_COMPLETED"), committed);
    }

    @Test
    void failureWhileSavingOutboxRollsBackRatherThanCommittingEarlierEvents() {
        OutboxEventRepository repository = context.getBean(OutboxEventRepository.class);
        when(repository.save(any(OutboxEvent.class))).thenAnswer(call -> {
            OutboxEvent event = call.getArgument(0);
            if ("TRANSACTION_COMPLETED".equals(event.getEventType())) {
                throw new IllegalStateException("Simulated outbox failure");
            }
            recordOnCommit(event.getEventType());
            return event;
        });
        assertThrows(IllegalStateException.class,
                () -> context.getBean(OrderExecutionService.class).executeSingleOrder(7L));
        assertTrue(committed.isEmpty());
    }

    @Test
    void outboxCannotBeSavedOutsideATransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> context.getBean(InvestmentEventService.class).saveTransactionEvent(
                        new TransactionCompletedEvent("owner@example.com", "BUY", "ABC", BigDecimal.ONE, BigDecimal.TEN)));
        verifyNoInteractions(context.getBean(OutboxEventRepository.class));
    }
}
