package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Config.ThreadPoolConfig;
import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Model.InvestmentOrder;
import com.ga.investmentportfolio.Repository.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real production pool; mock only order discovery and the transactional worker. */
class OrderSchedulerTest {
    final InvestmentOrderRepository repository = mock(InvestmentOrderRepository.class);
    final OrderExecutionService execution = mock(OrderExecutionService.class);
    final ThreadPoolTaskExecutor pool = new ThreadPoolConfig().orderExecutor();
    final InvestmentOrderService service = new InvestmentOrderService(repository, mock(UserRepository.class),
            mock(PortfolioRepository.class), mock(AssetRepository.class), mock(HoldingRepository.class),
            mock(TradeExecutionService.class), execution, mock(org.springframework.context.ApplicationEventPublisher.class), pool);

    @AfterEach void stop() throws Exception {
        pool.shutdown();
        assertTrue(pool.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS));
    }

    InvestmentOrder order(long id) { InvestmentOrder order = new InvestmentOrder(); order.setId(id); return order; }
    void batch(long... ids) { when(repository.findByStatus(OrderStatus.PENDING)).thenReturn(LongStream.of(ids).mapToObj(this::order).toList()); }
    static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(10, TimeUnit.SECONDS), "Worker barrier timed out"); }
    void idle() throws InterruptedException {
        Set<?> processing = (Set<?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "processingOrderIds");
        assertNotNull(processing);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!processing.isEmpty() || pool.getActiveCount() != 0 || !pool.getThreadPoolExecutor().getQueue().isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "Pool did not finish its tasks");
            Thread.sleep(10);
        }
    }

    @Test void scheduledDispatcherUsesSeparateExecutionBeanForEachPendingOrder() throws Exception {
        batch(1, 2);
        service.executePendingOrders(); idle();
        verify(execution).executeSingleOrder(1L); verify(execution).executeSingleOrder(2L);
        verifyNoMoreInteractions(execution);
        assertEquals(10000, InvestmentOrderService.class.getMethod("executePendingOrders").getAnnotation(Scheduled.class).fixedDelay());
    }

    @Test void emptyBatchDoesNotAttemptExecution() throws Exception {
        batch(); service.executePendingOrders(); idle(); verifyNoInteractions(execution);
    }

    @Test void multipleOrdersExecuteConcurrentlyOnWorkerThreads() throws Exception {
        batch(1, 2, 3);
        CountDownLatch entered = new CountDownLatch(3), release = new CountDownLatch(1);
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        Thread dispatcher = Thread.currentThread();
        doAnswer(call -> { workers.add(Thread.currentThread()); entered.countDown(); await(release); return null; })
                .when(execution).executeSingleOrder(anyLong());
        try {
            service.executePendingOrders();
            await(entered);
            assertEquals(3, workers.size()); assertFalse(workers.contains(dispatcher));
        } finally { release.countDown(); idle(); }
        for (long id : new long[]{1, 2, 3}) verify(execution).executeSingleOrder(id);
    }

    @Test void overlappingDispatchesSkipRunningAndQueuedOrdersThenAllowResubmission() throws Exception {
        batch(1, 2, 3, 4);
        CountDownLatch entered = new CountDownLatch(3), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); await(release); return null; }).when(execution).executeSingleOrder(anyLong());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            service.executePendingOrders(); await(entered);
            assertEquals(1, pool.getThreadPoolExecutor().getQueue().size());
            Future<?> first = callers.submit(service::executePendingOrders);
            Future<?> second = callers.submit(service::executePendingOrders);
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertEquals(1, pool.getThreadPoolExecutor().getQueue().size());
            for (long id : new long[]{1, 2, 3}) verify(execution).executeSingleOrder(id);
            verify(execution, never()).executeSingleOrder(4L);
        } finally {
            release.countDown(); idle(); callers.shutdownNow();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        }
        for (long id : new long[]{1, 2, 3, 4}) verify(execution).executeSingleOrder(id);
        service.executePendingOrders(); idle();
        for (long id : new long[]{1, 2, 3, 4}) verify(execution, times(2)).executeSingleOrder(id);
    }

    @Test void executionFailureIsLoggedAndRemainingBatchContinuesAndFailureCanRetry() throws Exception {
        batch(1, 2);
        CountDownLatch failing = new CountDownLatch(1), release = new CountDownLatch(1), otherFinished = new CountDownLatch(1);
        doAnswer(call -> { failing.countDown(); await(release); throw new IllegalStateException("Simulated execution failure"); })
                .when(execution).executeSingleOrder(1L);
        doAnswer(call -> { otherFinished.countDown(); return null; }).when(execution).executeSingleOrder(2L);
        Logger logger = (Logger) LoggerFactory.getLogger(InvestmentOrderService.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            try {
                assertDoesNotThrow(service::executePendingOrders);
                await(failing); await(otherFinished);
            } finally { release.countDown(); idle(); }
            verify(execution).executeSingleOrder(1L); verify(execution).executeSingleOrder(2L);
            verifyNoMoreInteractions(execution);
            var errors = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertEquals(1, errors.size());
            assertEquals("Failed to execute order ID: 1", errors.get(0).getFormattedMessage());
            assertNotNull(errors.get(0).getThrowableProxy());
            assertEquals(IllegalStateException.class.getName(), errors.get(0).getThrowableProxy().getClassName());
            assertEquals("Simulated execution failure", errors.get(0).getThrowableProxy().getMessage());
            doNothing().when(execution).executeSingleOrder(1L);
            batch(1); service.executePendingOrders(); idle();
            verify(execution, times(2)).executeSingleOrder(1L);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void saturatedPoolRejectsWithoutRunningOnCallerAndRejectedOrderCanRetry() throws Exception {
        assertEquals(3, pool.getCorePoolSize()); assertEquals(5, pool.getMaxPoolSize()); assertEquals(10, pool.getQueueCapacity());
        batch(LongStream.rangeClosed(1, 16).toArray());
        CountDownLatch entered = new CountDownLatch(5), release = new CountDownLatch(1);
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> { workers.add(Thread.currentThread()); attempts.incrementAndGet(); entered.countDown(); await(release); return null; })
                .when(execution).executeSingleOrder(anyLong());
        Logger logger = (Logger) LoggerFactory.getLogger(InvestmentOrderService.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            try {
                assertDoesNotThrow(service::executePendingOrders); await(entered);
                assertEquals(5, pool.getPoolSize()); assertEquals(10, pool.getThreadPoolExecutor().getQueue().size());
                assertFalse(workers.contains(Thread.currentThread()));
                verify(execution, never()).executeSingleOrder(16L);
                var errors = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
                assertEquals(1, errors.size()); assertEquals("Failed to execute order ID: 16", errors.get(0).getFormattedMessage());
                assertNotNull(errors.get(0).getThrowableProxy());
                assertEquals(org.springframework.core.task.TaskRejectedException.class.getName(), errors.get(0).getThrowableProxy().getClassName());
            } finally { release.countDown(); idle(); }
            assertEquals(15, attempts.get());
            for (long id = 1; id <= 15; id++) verify(execution).executeSingleOrder(id);
            batch(16, 17); service.executePendingOrders(); idle();
            verify(execution).executeSingleOrder(16L); verify(execution).executeSingleOrder(17L);
            assertEquals(17, attempts.get()); verifyNoMoreInteractions(execution);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void rejectedSubmissionDoesNotPreventLaterOrderInSameBatch() throws Exception {
        var rejecting = mock(ThreadPoolTaskExecutor.class);
        var dispatcher = new InvestmentOrderService(repository, mock(UserRepository.class), mock(PortfolioRepository.class),
                mock(AssetRepository.class), mock(HoldingRepository.class), mock(TradeExecutionService.class), execution, mock(org.springframework.context.ApplicationEventPublisher.class), rejecting);
        doThrow(new org.springframework.core.task.TaskRejectedException("saturated"))
                .doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(rejecting).execute(any(Runnable.class));
        batch(1, 2); assertDoesNotThrow(dispatcher::executePendingOrders);
        verify(execution, never()).executeSingleOrder(1L); verify(execution).executeSingleOrder(2L);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(rejecting).execute(any(Runnable.class));
        batch(1); dispatcher.executePendingOrders(); verify(execution).executeSingleOrder(1L);
    }
}
