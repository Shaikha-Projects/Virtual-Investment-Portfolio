package com.ga.investmentportfolio.Concurrency;

import com.ga.investmentportfolio.Enums.*;
import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.InvestmentOrder;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.AssetRepository;
import com.ga.investmentportfolio.Repository.InvestmentOrderRepository;
import com.ga.investmentportfolio.Repository.PortfolioRepository;
import com.ga.investmentportfolio.Repository.UserRepository;
import com.ga.investmentportfolio.Repository.TransactionRepository;
import com.ga.investmentportfolio.Config.AdminDataInitializer;
import com.ga.investmentportfolio.Security.JWTUtils;
import com.ga.investmentportfolio.Service.EmailService;
import com.ga.investmentportfolio.Service.InvestmentOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.PageRequest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = OrderExecutionConcurrencyTest.TestDatabaseGuard.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class OrderExecutionConcurrencyTest {
    private static final Logger log = LoggerFactory.getLogger(OrderExecutionConcurrencyTest.class);
    private static final int ORDER_COUNT = 12;

    // This runs BEFORE Hibernate can create/drop tables, unlike an ordinary @Test.
    public static class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            String url = context.getEnvironment().getRequiredProperty("spring.datasource.url");
            assertTrue(url.startsWith("jdbc:postgresql://"), "This test requires PostgreSQL");
            assertEquals("/investment_portfolio_test", URI.create(url.substring(5)).getPath(),
                    "Refusing to start against a database other than investment_portfolio_test");
        }
    }

    // These unrelated beans otherwise require dev-only JWT, SMTP and admin properties.
    @MockitoBean private JWTUtils jwtUtils;
    @MockitoBean private EmailService emailService;
    @MockitoBean private AdminDataInitializer adminDataInitializer;
    @Autowired
    private InvestmentOrderService investmentOrderService;

    @Autowired
    @Qualifier("orderExecutor")
    private ThreadPoolTaskExecutor orderExecutor;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private InvestmentOrderRepository investmentOrderRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    // Keep all fixture IDs so each order and portfolio can be checked.
    private final List<Long> orderIds = new ArrayList<>();
    private final List<Long> portfolioIds = new ArrayList<>();

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        // Confirm the ACTUAL connected database before writing any fixture data.
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("investment_portfolio_test", connection.getCatalog());
            log.info("TEST DATABASE: {}", connection.getCatalog());
        }
        orderIds.clear();
        portfolioIds.clear();
        String runId = UUID.randomUUID().toString();
        //create asset
        Asset asset = new Asset();

        asset.setSymbol("TESTCONC-" + runId);
        asset.setName("Concurrency Test Asset");
        asset.setAssetType(AssetType.STOCK);
        asset.setCurrentPrice(new BigDecimal("100.00"));
        asset.setAssetStatus(AssetStatus.ACTIVE);

        asset = assetRepository.save(asset);

        //create 12 orders using a loop
        for (int i = 1; i <= ORDER_COUNT; i++) {

            //creating user
            User user = new User();
            user.setFirstName("Test");
            user.setLastName("Investor");
            user.setPhoneNumber("12345678");
            user.setEmailAddress("investor" + i + "-" + runId + "@example.test");
            user.setPassword("testPassword");
            user.setRole(Role.USER);
            user.setStatus(UserStatus.ACTIVE);

            user = userRepository.save(user);

            //creating portfolio
            Portfolio portfolio = new Portfolio();

            portfolio.setUser(user);
            portfolio.setCashBalance(new BigDecimal("1000.00"));
            portfolio.setReservedCash(new BigDecimal("110.00"));

            portfolio = portfolioRepository.save(portfolio);
            portfolioIds.add(portfolio.getId());

            //create pending order
            InvestmentOrder order = new InvestmentOrder();

            order.setPortfolio(portfolio);
            order.setAsset(asset);
            order.setOrderType(TransactionType.BUY);
            order.setQuantity(new BigDecimal("1.0000"));
            order.setLimitPrice(new BigDecimal("110.00"));
            order.setStatus(OrderStatus.PENDING);

            order = investmentOrderRepository.save(order);

            //add order to the tracker
            orderIds.add(order.getId());
        }
        assertEquals(ORDER_COUNT, orderIds.size());
        assertEquals(ORDER_COUNT, Set.copyOf(orderIds).size(), "Each order must have a different ID");
    }

    @Test
    void shouldExecuteMultipleOrdersConcurrently() throws InterruptedException {

        log.info("========== ORDER CONCURRENCY TEST ==========");
        long transactionsBefore = transactionRepository.count();
        Thread testThread = Thread.currentThread();
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        AtomicInteger submitted = new AtomicInteger();
        CountDownLatch threeWorkersReady = new CountDownLatch(3);
        CountDownLatch startWork = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(ORDER_COUNT);

        // A decorator observes real pool tasks; it does not mock the execution service.
        // The gate makes three overlapping workers visible without relying on timing.
        orderExecutor.setTaskDecorator(task -> {
            int taskNumber = submitted.incrementAndGet();
            return () -> {
                Thread worker = Thread.currentThread();
                workers.add(worker);
                log.info("READY task={} worker={}", taskNumber, worker.getName());
                threeWorkersReady.countDown();
                try {
                    if (!startWork.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Worker start gate timed out");
                    }
                    log.info("START task={} worker={}", taskNumber, worker.getName());
                    task.run(); // Real order service, PostgreSQL locks and transaction commit.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Worker interrupted", exception);
                } finally {
                    log.info("END task={} worker={}", taskNumber, worker.getName());
                    finished.countDown();
                }
            };
        });

        try {
            investmentOrderService.executePendingOrders();
            assertTrue(threeWorkersReady.await(10, TimeUnit.SECONDS), "Three real pool workers must be ready together");
            assertTrue(workers.size() >= 3);
            assertTrue(!workers.contains(testThread), "Orders must run outside the test thread");
            assertEquals(ORDER_COUNT, finished.getCount(), "All workers are still waiting at the gate");
            startWork.countDown();
            assertTrue(finished.await(30, TimeUnit.SECONDS), "All 12 submitted tasks must finish");
            assertEquals(ORDER_COUNT, submitted.get());
            assertTrue(workers.stream().allMatch(worker -> worker.getName().startsWith("order-worker-")));

            for (int i = 0; i < ORDER_COUNT; i++) {
                InvestmentOrder order = investmentOrderRepository.findById(orderIds.get(i)).orElseThrow();
                assertEquals(OrderStatus.FILLED, order.getStatus(), "Order " + order.getId());
                assertNotNull(order.getExecutedAt());

                Portfolio portfolio = portfolioRepository.findById(portfolioIds.get(i)).orElseThrow();
                assertEquals(0, new BigDecimal("900.00").compareTo(portfolio.getCashBalance()));
                assertEquals(0, BigDecimal.ZERO.compareTo(portfolio.getReservedCash()));

                var trades = transactionRepository.findByPortfolio(portfolio, PageRequest.of(0, 20));
                assertEquals(1, trades.getTotalElements(), "Exactly one transaction per order's portfolio");
                var trade = trades.getContent().get(0);
                assertEquals(TransactionType.BUY, trade.getTransactionType());
                assertEquals(TransactionStatus.COMPLETED, trade.getTransactionStatus());
                assertEquals(order.getAsset().getId(), trade.getAsset().getId());
                assertEquals(0, BigDecimal.ONE.compareTo(trade.getQuantity()));
                assertEquals(0, new BigDecimal("100.00").compareTo(trade.getPricePerUnit()));
                assertEquals(0, new BigDecimal("100.00").compareTo(trade.getTotalAmount()));
                log.info("FILLED order={} portfolio={} transaction={}", order.getId(), portfolio.getId(), trade.getId());
            }

            // A second dispatch must not execute the already-filled orders again.
            investmentOrderService.executePendingOrders();
            assertEquals(ORDER_COUNT, submitted.get());
            assertEquals(transactionsBefore + ORDER_COUNT, transactionRepository.count());
            log.info("PASS: {} FILLED orders, {} transactions, {} real worker threads", ORDER_COUNT, ORDER_COUNT, workers.size());
        } finally {
            startWork.countDown();
            try {
                assertTrue(finished.await(30, TimeUnit.SECONDS), "Workers must finish before the test context closes");
            } finally {
                orderExecutor.setTaskDecorator(null);
            }
        }
    }

}
