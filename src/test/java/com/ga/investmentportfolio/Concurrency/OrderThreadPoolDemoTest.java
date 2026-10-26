package com.ga.investmentportfolio.Concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;


public class OrderThreadPoolDemoTest {

    private ThreadPoolTaskExecutor orderExecutor;

    @BeforeEach
    void setUp() {
        orderExecutor = new ThreadPoolTaskExecutor();

        orderExecutor.setCorePoolSize(3);
        orderExecutor.setMaxPoolSize(5);
        orderExecutor.setQueueCapacity(10);
        orderExecutor.setThreadNamePrefix("order-worker-");

        orderExecutor.initialize();
    }

    @Test
    void shouldRunOrdersUsingMultipleThreads() throws InterruptedException {

        System.out.println("========== ORDER THREAD POOL DEMO ==========");
        System.out.println("Core Threads    : " + orderExecutor.getCorePoolSize());
        System.out.println("Total Orders    : 12");
        System.out.println("============================================");
        CountDownLatch latch = new CountDownLatch(12);
        for (int i = 1; i <= 12; i++) {

            int orderId = i;

            orderExecutor.execute(() -> {
                String worker = Thread.currentThread().getName();
                try {
                    System.out.println(worker + " -> START processing Order " + orderId);
                    Thread.sleep(1000);
                    System.out.println(worker + " -> FINISHED Order " + orderId);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        boolean completed = latch.await(15, TimeUnit.SECONDS);

        assertTrue(completed, "Not all tasks finished");

        int finishedTasks = 12 - (int) latch.getCount();

        System.out.println("==================================");
        System.out.println("Total Tasks     : 12");
        System.out.println("Completed Tasks : " + finishedTasks);
        System.out.println("==================================");
        System.out.println("ALL TASKS COMPLETED");

    }

    @AfterEach
    void tearDown() {
        orderExecutor.shutdown();
    }
}
