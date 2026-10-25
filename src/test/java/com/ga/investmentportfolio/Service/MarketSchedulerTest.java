package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Config.ThreadPoolConfig;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Model.Asset;
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

/** Real production pool; mock only asset discovery and the transactional worker. */
class MarketSchedulerTest {
    final AssetRepository repository = mock(AssetRepository.class);
    final MarketPriceUpdateService execution = mock(MarketPriceUpdateService.class);
    final ThreadPoolTaskExecutor pool = new ThreadPoolConfig().marketExecutor();
    final MarketSimulationService service = new MarketSimulationService(repository, mock(PriceHistoryRepository.class), execution, pool);

    @AfterEach void stop() throws Exception {
        pool.shutdown();
        assertTrue(pool.getThreadPoolExecutor().awaitTermination(10, TimeUnit.SECONDS));
    }

    Asset asset(long id) { Asset asset = new Asset(); asset.setId(id); return asset; }
    void batch(long... ids) { when(repository.findByAssetStatus(AssetStatus.ACTIVE)).thenReturn(LongStream.of(ids).mapToObj(this::asset).toList()); }
    static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(10, TimeUnit.SECONDS), "Worker barrier timed out"); }
    void idle() throws InterruptedException {
        Set<?> processing = (Set<?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "processingAssetIds");
        assertNotNull(processing);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!processing.isEmpty() || pool.getActiveCount() != 0 || !pool.getThreadPoolExecutor().getQueue().isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "Pool did not finish its tasks");
            Thread.sleep(10);
        }
    }

    @Test void scheduledDispatcherUsesSeparateExecutionBeanForEachActiveAsset() throws Exception {
        batch(1, 2);
        service.updateMarketPrices(); idle();
        verify(execution).updateAssetPrice(1L); verify(execution).updateAssetPrice(2L);
        verifyNoMoreInteractions(execution);
        verify(repository).findByAssetStatus(AssetStatus.ACTIVE);
        verifyNoMoreInteractions(repository);
        assertEquals(10000, MarketSimulationService.class.getMethod("updateMarketPrices").getAnnotation(Scheduled.class).fixedDelay());
    }

    @Test void emptyBatchDoesNotAttemptExecution() throws Exception {
        batch(); service.updateMarketPrices(); idle(); verifyNoInteractions(execution);
    }

    @Test void multipleAssetsExecuteConcurrentlyOnWorkerThreads() throws Exception {
        batch(1, 2, 3);
        CountDownLatch entered = new CountDownLatch(3), release = new CountDownLatch(1);
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        Thread dispatcher = Thread.currentThread();
        doAnswer(call -> { workers.add(Thread.currentThread()); entered.countDown(); await(release); return null; })
                .when(execution).updateAssetPrice(anyLong());
        try {
            service.updateMarketPrices();
            await(entered);
            assertEquals(3, workers.size()); assertFalse(workers.contains(dispatcher));
            workers.forEach(worker -> assertTrue(worker.getName().startsWith("market-worker-")));
        } finally { release.countDown(); idle(); }
        for (long id : new long[]{1, 2, 3}) verify(execution).updateAssetPrice(id);
    }

    @Test void overlappingDispatchesSkipRunningAndQueuedAssetsThenAllowResubmission() throws Exception {
        batch(1, 2, 3, 4);
        CountDownLatch entered = new CountDownLatch(3), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); await(release); return null; }).when(execution).updateAssetPrice(anyLong());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            service.updateMarketPrices(); await(entered);
            assertEquals(1, pool.getThreadPoolExecutor().getQueue().size());
            Future<?> first = callers.submit(service::updateMarketPrices);
            Future<?> second = callers.submit(service::updateMarketPrices);
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertEquals(1, pool.getThreadPoolExecutor().getQueue().size());
            for (long id : new long[]{1, 2, 3}) verify(execution).updateAssetPrice(id);
            verify(execution, never()).updateAssetPrice(4L);
        } finally {
            release.countDown(); idle(); callers.shutdownNow();
            assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
        }
        for (long id : new long[]{1, 2, 3, 4}) verify(execution).updateAssetPrice(id);
        service.updateMarketPrices(); idle();
        for (long id : new long[]{1, 2, 3, 4}) verify(execution, times(2)).updateAssetPrice(id);
    }

    @Test void executionFailureIsLoggedAndRemainingBatchContinuesAndFailureCanRetry() throws Exception {
        batch(1, 2);
        CountDownLatch failing = new CountDownLatch(1), release = new CountDownLatch(1), otherFinished = new CountDownLatch(1);
        doAnswer(call -> { failing.countDown(); await(release); throw new IllegalStateException("Simulated execution failure"); })
                .when(execution).updateAssetPrice(1L);
        doAnswer(call -> { otherFinished.countDown(); return null; }).when(execution).updateAssetPrice(2L);
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            try {
                assertDoesNotThrow(service::updateMarketPrices);
                await(failing); await(otherFinished);
            } finally { release.countDown(); idle(); }
            verify(execution).updateAssetPrice(1L); verify(execution).updateAssetPrice(2L);
            verifyNoMoreInteractions(execution);
            var errors = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertEquals(1, errors.size());
            assertEquals("Failed to update Asset ID: 1", errors.get(0).getFormattedMessage());
            assertNotNull(errors.get(0).getThrowableProxy());
            assertEquals(IllegalStateException.class.getName(), errors.get(0).getThrowableProxy().getClassName());
            assertEquals("Simulated execution failure", errors.get(0).getThrowableProxy().getMessage());
            doNothing().when(execution).updateAssetPrice(1L);
            batch(1); service.updateMarketPrices(); idle();
            verify(execution, times(2)).updateAssetPrice(1L);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void saturatedPoolRejectsWithoutRunningOnCallerAndRejectedAssetCanRetry() throws Exception {
        assertEquals(3, pool.getCorePoolSize()); assertEquals(5, pool.getMaxPoolSize()); assertEquals(20, pool.getQueueCapacity());
        batch(LongStream.rangeClosed(1, 26).toArray());
        CountDownLatch entered = new CountDownLatch(5), release = new CountDownLatch(1);
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> { workers.add(Thread.currentThread()); attempts.incrementAndGet(); entered.countDown(); await(release); return null; })
                .when(execution).updateAssetPrice(anyLong());
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            try {
                assertDoesNotThrow(service::updateMarketPrices); await(entered);
                assertEquals(5, pool.getPoolSize()); assertEquals(20, pool.getThreadPoolExecutor().getQueue().size());
                assertFalse(workers.contains(Thread.currentThread()));
                verify(execution, never()).updateAssetPrice(26L);
                var errors = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
                assertEquals(1, errors.size()); assertEquals("Failed to execute Asset ID: 26", errors.get(0).getFormattedMessage());
                assertNotNull(errors.get(0).getThrowableProxy());
                assertEquals(org.springframework.core.task.TaskRejectedException.class.getName(), errors.get(0).getThrowableProxy().getClassName());
            } finally { release.countDown(); idle(); }
            assertEquals(25, attempts.get());
            for (long id = 1; id <= 25; id++) verify(execution).updateAssetPrice(id);
            batch(26, 27); service.updateMarketPrices(); idle();
            verify(execution).updateAssetPrice(26L); verify(execution).updateAssetPrice(27L);
            assertEquals(27, attempts.get()); verifyNoMoreInteractions(execution);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void rejectedSubmissionDoesNotPreventLaterAssetInSameBatch() throws Exception {
        var rejecting = mock(ThreadPoolTaskExecutor.class);
        var dispatcher = new MarketSimulationService(repository, mock(PriceHistoryRepository.class), execution, rejecting);
        doThrow(new org.springframework.core.task.TaskRejectedException("saturated"))
                .doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(rejecting).execute(any(Runnable.class));
        batch(1, 2); assertDoesNotThrow(dispatcher::updateMarketPrices);
        verify(execution, never()).updateAssetPrice(1L); verify(execution).updateAssetPrice(2L);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; }).when(rejecting).execute(any(Runnable.class));
        batch(1); dispatcher.updateMarketPrices(); verify(execution).updateAssetPrice(1L);
    }
    @Test void marketFailuresUseMarketServiceLogger() throws Exception {
        batch(1);
        doThrow(new IllegalStateException("market failure")).when(execution).updateAssetPrice(1L);
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            service.updateMarketPrices(); idle();
            var errors = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertEquals(1, errors.size());
            assertEquals(MarketSimulationService.class.getName(), errors.get(0).getLoggerName());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
