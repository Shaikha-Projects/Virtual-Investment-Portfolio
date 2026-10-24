package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Model.InvestmentOrder;
import com.ga.investmentportfolio.Repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Scheduler dispatch tests only. Database locking is tested separately on PostgreSQL. */
class OrderSchedulerTest {
    final InvestmentOrderRepository repository = mock(InvestmentOrderRepository.class);
    final OrderExecutionService execution = mock(OrderExecutionService.class);
    final InvestmentOrderService service = new InvestmentOrderService(repository, mock(UserRepository.class),
            mock(PortfolioRepository.class), mock(AssetRepository.class), mock(HoldingRepository.class),
            mock(TradeExecutionService.class), execution);

    InvestmentOrder order(long id) { InvestmentOrder order = new InvestmentOrder(); order.setId(id); return order; }

    @Test void scheduledDispatcherUsesSeparateExecutionBeanForEachPendingOrder() throws Exception {
        when(repository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order(1), order(2)));
        service.executePendingOrders();
        var sequence = inOrder(execution);
        sequence.verify(execution).executeSingleOrder(1L);
        sequence.verify(execution).executeSingleOrder(2L);
        assertEquals(10000, InvestmentOrderService.class.getMethod("executePendingOrders").getAnnotation(Scheduled.class).fixedDelay());
    }

    @Test void emptyBatchDoesNotAttemptExecution() {
        when(repository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of());
        service.executePendingOrders(); verifyNoInteractions(execution);
    }

    @Test void executionFailureIsLoggedAndRemainingBatchContinues() {
        when(repository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(order(1), order(2)));
        doThrow(new IllegalStateException("Simulated execution failure")).when(execution).executeSingleOrder(1L);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(InvestmentOrderService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(service::executePendingOrders);
            var sequence = inOrder(execution);
            sequence.verify(execution).executeSingleOrder(1L);
            sequence.verify(execution).executeSingleOrder(2L);
            verifyNoMoreInteractions(execution);
            var errors = appender.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR).toList();
            assertEquals(1, errors.size());
            assertEquals("Failed to execute order ID: 1", errors.get(0).getFormattedMessage());
            assertNotNull(errors.get(0).getThrowableProxy());
            assertEquals(IllegalStateException.class.getName(), errors.get(0).getThrowableProxy().getClassName());
            assertEquals("Simulated execution failure", errors.get(0).getThrowableProxy().getMessage());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
