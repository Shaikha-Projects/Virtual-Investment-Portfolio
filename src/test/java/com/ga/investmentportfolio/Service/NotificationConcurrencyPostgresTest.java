package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Config.ThreadPoolConfig;
import com.ga.investmentportfolio.Controller.InvestmentOrderController;
import com.ga.investmentportfolio.Controller.PortfolioController;
import com.ga.investmentportfolio.DTO.Request.*;
import com.ga.investmentportfolio.Enums.*;
import com.ga.investmentportfolio.Enums.Role;
import com.ga.investmentportfolio.Event.*;
import com.ga.investmentportfolio.Exception.GlobalExceptionHandler;
import com.ga.investmentportfolio.Model.*;
import com.ga.investmentportfolio.Repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL, financial services, event publisher, AFTER_COMMIT listener and async proxy.
 * Mock only the SSE transport boundary and audit side effects. No background schedulers.
 */
@EnabledIfEnvironmentVariable(named = "ORDER_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class NotificationConcurrencyPostgresTest {
    static AnnotationConfigApplicationContext context;
    static String schema;
    EntityManager em;
    TransactionTemplate tx;
    AssetService trades;
    InvestmentOrderService orders;
    OrderExecutionService execution;
    NotificationService transport;
    EventProbe probe;
    final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    long assetId, portfolioId;
    String email;
    record Delivery(String email, String message, Thread thread, boolean callerTransactionActive, long trades, BigDecimal cash) {}
    static class EventProbe {
        final List<InvestmentNotificationEvent> events = new CopyOnWriteArrayList<>();
        @EventListener public void published(InvestmentNotificationEvent event) { events.add(event); }
    }
    static String url() { return System.getenv("ORDER_TEST_JDBC_URL"); }
    static String user() { return Optional.ofNullable(System.getenv("ORDER_TEST_DB_USER")).orElse("postgres"); }
    static String password() { return Optional.ofNullable(System.getenv("ORDER_TEST_DB_PASSWORD")).orElse(""); }
    static BigDecimal bd(String value) { return new BigDecimal(value); }
    static void amount(String expected, BigDecimal actual) { assertEquals(0, bd(expected).compareTo(actual), "Actual amount: " + actual); }

    @BeforeAll static void start() throws Exception {
        schema = "notification_review_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(), user(), password()); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        context = new AnnotationConfigApplicationContext(Config.class);
    }
    @AfterAll static void stop() throws Exception {
        if (context != null) context.close();
        if (schema != null) try (var connection = DriverManager.getConnection(url(), user(), password()); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = AssetRepository.class)
    @Import({ThreadPoolConfig.class, AssetService.class, InvestmentOrderService.class, OrderExecutionService.class,
            TradeExecutionService.class, InvestmentNotificationListener.class, EventProbe.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + schema, user(), password());
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource); factory.setPackagesToScan(Asset.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.show_sql", "false")); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean AuditLogService auditLogService() { return mock(AuditLogService.class); }
        @Bean NotificationService notificationService() { return mock(NotificationService.class); }
    }
    @BeforeEach void fixture() {
        em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        trades = context.getBean(AssetService.class); orders = context.getBean(InvestmentOrderService.class);
        execution = context.getBean(OrderExecutionService.class); transport = context.getBean(NotificationService.class);
        probe = context.getBean(EventProbe.class); probe.events.clear(); deliveries.clear(); reset(transport);
        tx.executeWithoutResult(status -> {
            for (String entity : List.of("PriceHistory", "Transaction", "InvestmentOrder", "Holding", "Portfolio", "User", "Asset"))
                em.createQuery("delete from " + entity).executeUpdate();
            Asset asset = new Asset(); asset.setSymbol("REVIEW"); asset.setName("Review"); asset.setAssetType(AssetType.STOCK);
            asset.setAssetStatus(AssetStatus.ACTIVE); asset.setCurrentPrice(bd("90")); em.persist(asset); assetId = asset.getId();
            User user = new User(); email = "notification-" + UUID.randomUUID() + "@example.test";
            user.setEmailAddress(email); user.setFirstName("Notification"); user.setLastName("Review");
            user.setPhoneNumber("0000000000"); user.setPassword("test-only"); user.setRole(Role.USER); user.setStatus(UserStatus.ACTIVE); em.persist(user);
            Portfolio portfolio = new Portfolio(); portfolio.setUser(user); portfolio.setCashBalance(bd("1000"));
            em.persist(portfolio); user.setPortfolio(portfolio); portfolioId = portfolio.getId();
            Holding holding = new Holding(); holding.setPortfolio(portfolio); holding.setAsset(asset);
            holding.setQuantity(bd("10")); holding.setAverageBuyPrice(bd("50")); em.persist(holding);
        });
        doAnswer(call -> { recordDelivery(call.getArgument(0), call.getArgument(1)); return null; })
                .when(transport).sendNotification(anyString(), anyString());
    }
    <T> T read(Supplier<T> action) { return tx.execute(status -> action.get()); }
    long tradeCount() { return read(() -> em.createQuery("select count(t) from Transaction t", Long.class).getSingleResult()); }
    BigDecimal cash() { return read(() -> em.find(Portfolio.class, portfolioId).getCashBalance()); }
    void recordDelivery(String address, String message) {
        boolean active = TransactionSynchronizationManager.isActualTransactionActive();
        deliveries.add(new Delivery(address, message, Thread.currentThread(), active, tradeCount(), cash()));
    }
    ThreadPoolTaskExecutor pool(String name) { return context.getBean(name, ThreadPoolTaskExecutor.class); }
    void awaitCondition(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            try { Thread.sleep(10); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }
    void notificationIdle() {
        var executor = pool("notificationExecutor").getThreadPoolExecutor();
        awaitCondition(() -> executor.getCompletedTaskCount() == executor.getTaskCount() && executor.getActiveCount() == 0 && executor.getQueue().isEmpty(), "Notifications did not finish");
    }
    void orderIdle() {
        Object target = AopTestUtils.getUltimateTargetObject(orders);
        Set<?> ids = (Set<?>) ReflectionTestUtils.getField(target, "processingOrderIds"); assertNotNull(ids);
        awaitCondition(() -> ids.isEmpty() && pool("orderExecutor").getActiveCount() == 0 && pool("orderExecutor").getThreadPoolExecutor().getQueue().isEmpty(), "Orders did not finish");
    }
    @AfterEach void drain() { orderIdle(); notificationIdle(); }
    void buy() {
        BuyAssetRequest request = new BuyAssetRequest(); ReflectionTestUtils.setField(request, "assetId", assetId); ReflectionTestUtils.setField(request, "quantity", bd("2"));
        trades.buyAsset(email, request);
    }
    void sell() {
        SellAssetRequest request = new SellAssetRequest(); ReflectionTestUtils.setField(request, "assetId", assetId); ReflectionTestUtils.setField(request, "quantity", bd("2"));
        trades.sellAsset(email, request);
    }
    long place(TransactionType type) {
        CreateOrderRequest request = new CreateOrderRequest(); ReflectionTestUtils.setField(request, "assetId", assetId);
        ReflectionTestUtils.setField(request, "orderType", type); ReflectionTestUtils.setField(request, "quantity", bd("2"));
        ReflectionTestUtils.setField(request, "limitPrice", bd(type == TransactionType.BUY ? "100" : "80"));
        orders.placeLimitOrder(email, request);
        return read(() -> em.createQuery("select max(o.id) from InvestmentOrder o", Long.class).getSingleResult());
    }
    long prepare(String scenario) { return scenario.startsWith("DIRECT") ? 0 : place(scenario.endsWith("BUY") ? TransactionType.BUY : TransactionType.SELL); }
    void operate(String scenario, long id) {
        switch (scenario) {
            case "DIRECT_BUY" -> buy(); case "DIRECT_SELL" -> sell();
            case "LIMIT_BUY", "LIMIT_SELL" -> execution.executeSingleOrder(id);
            default -> orders.cancelOrder(email, id);
        }
    }
    void assertMessage(String expected) {
        awaitCondition(() -> deliveries.size() == 1, "Expected one notification delivery");
        notificationIdle(); assertEquals(1, probe.events.size()); assertEquals(1, deliveries.size());
        assertEquals(email, probe.events.get(0).getEmail()); assertEquals(expected, probe.events.get(0).getMessage());
        assertEquals(email, deliveries.get(0).email()); assertEquals(expected, deliveries.get(0).message());
    }
    @Test void directBuyPublishesCorrectEventAndDeliversCommittedStateOnNotificationWorker() {
        Thread caller = Thread.currentThread(); buy(); assertMessage("Purchase completed: 2 shares of REVIEW");
        Delivery delivery = deliveries.get(0); assertNotSame(caller, delivery.thread());
        assertTrue(delivery.thread().getName().startsWith("notification-worker-")); assertFalse(delivery.callerTransactionActive());
        assertEquals(1, delivery.trades()); amount("820", delivery.cash()); amount("820", cash());
    }
    @Test void directSellPublishesCorrectEvent() {
        sell(); assertMessage("Sale completed: 2 shares of REVIEW"); assertEquals(1, tradeCount()); amount("1180", cash());
    }
    @ParameterizedTest @ValueSource(strings = {"BUY", "SELL"})
    void automaticLimitOrdersPublishExactlyOneNotification(String type) {
        long id = place(TransactionType.valueOf(type)); assertTrue(probe.events.isEmpty());
        orders.executePendingOrders(); orderIdle();
        assertMessage("Limit " + type + " order executed: 2.0000 shares of REVIEW");
        assertEquals(OrderStatus.FILLED, read(() -> em.find(InvestmentOrder.class, id).getStatus()));
        orders.executePendingOrders(); orderIdle(); notificationIdle(); assertEquals(1, deliveries.size()); assertEquals(1, tradeCount());
    }
    @ParameterizedTest @ValueSource(strings = {"BUY", "SELL"})
    void cancellationPublishesCancellationRatherThanExecution(String type) {
        long id = place(TransactionType.valueOf(type)); orders.cancelOrder(email, id);
        assertMessage("Limit order cancelled: 2.0000 shares of REVIEW");
        assertEquals(OrderStatus.CANCELLED, read(() -> em.find(InvestmentOrder.class, id).getStatus())); assertEquals(0, tradeCount());
    }
    @Test void eventIsPublishedInsideTransactionButDeliveredOnlyAfterCommit() {
        tx.executeWithoutResult(status -> {
            buy(); assertEquals(1, probe.events.size()); verifyNoInteractions(transport); assertTrue(deliveries.isEmpty());
            try (var connection = DriverManager.getConnection(url() + (url().contains("?") ? "&" : "?") + "currentSchema=" + schema, user(), password());
                 var statement = connection.createStatement(); var result = statement.executeQuery("select count(*) from transactions")) {
                assertTrue(result.next()); assertEquals(0, result.getLong(1));
            } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
        });
        awaitCondition(() -> deliveries.size() == 1, "After-commit notification missing");
        notificationIdle(); assertEquals(1, deliveries.size()); assertEquals(1, deliveries.get(0).trades());
    }
    @ParameterizedTest @ValueSource(strings = {"DIRECT_BUY", "DIRECT_SELL", "LIMIT_BUY", "LIMIT_SELL", "CANCEL_BUY", "CANCEL_SELL"})
    void explicitRollbackDiscardsNotificationsAndFinancialChanges(String scenario) {
        long id = prepare(scenario);
        tx.executeWithoutResult(status -> { operate(scenario, id); assertEquals(1, probe.events.size()); verifyNoInteractions(transport); status.setRollbackOnly(); });
        notificationIdle(); verifyNoInteractions(transport); assertTrue(deliveries.isEmpty()); amount("1000", cash()); assertEquals(0, tradeCount());
        read(() -> {
            Holding holding = em.createQuery("from Holding", Holding.class).getSingleResult(); amount("10", holding.getQuantity());
            if (id != 0) { assertEquals(OrderStatus.PENDING, em.find(InvestmentOrder.class, id).getStatus()); assertNull(em.find(InvestmentOrder.class, id).getExecutedAt()); }
            amount(id != 0 && scenario.endsWith("BUY") ? "200" : "0", em.find(Portfolio.class, portfolioId).getReservedCash());
            amount(id != 0 && scenario.endsWith("SELL") ? "2" : "0", holding.getReservedQuantity()); return null;
        });
    }
    @ParameterizedTest @ValueSource(strings = {"DIRECT_BUY", "DIRECT_SELL", "LIMIT_BUY", "LIMIT_SELL", "CANCEL_BUY", "CANCEL_SELL"})
    void databaseCommitFailureAfterEventPublicationDiscardsNotification(String scenario) {
        long id = prepare(scenario);
        String constraint = scenario.startsWith("CANCEL") ? "ALTER TABLE investment_orders ADD CONSTRAINT reject_notification_commit CHECK (status <> 'CANCELLED')" :
                "ALTER TABLE portfolios ADD CONSTRAINT reject_notification_commit CHECK (cashBalance " + (scenario.endsWith("BUY") ? ">=" : "<=") + " 1000)";
        sql(constraint);
        try {
            assertThrows(RuntimeException.class, () -> operate(scenario, id)); assertEquals(1, probe.events.size());
            notificationIdle(); verifyNoInteractions(transport); amount("1000", cash()); assertEquals(0, tradeCount());
            if (id != 0) assertEquals(OrderStatus.PENDING, read(() -> em.find(InvestmentOrder.class, id).getStatus()));
        } finally { sql("ALTER TABLE " + (scenario.startsWith("CANCEL") ? "investment_orders" : "portfolios") + " DROP CONSTRAINT reject_notification_commit"); }
    }
    void sql(String statement) { tx.executeWithoutResult(status -> em.createNativeQuery(statement).executeUpdate()); }
    @Test void deliveryExceptionDoesNotAffectCommittedTradeOrLaterNotifications() {
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> { recordDelivery(call.getArgument(0), call.getArgument(1)); if (first.getAndSet(false)) throw new IllegalStateException("transport unavailable"); return null; })
                .when(transport).sendNotification(anyString(), anyString());
        assertDoesNotThrow(this::buy); awaitCondition(() -> deliveries.size() == 1, "First notification missing"); notificationIdle(); amount("820", cash()); assertEquals(1, tradeCount());
        assertDoesNotThrow(this::sell); awaitCondition(() -> deliveries.size() == 2, "Second notification missing"); notificationIdle(); amount("1000", cash()); assertEquals(2, tradeCount()); assertEquals(2, deliveries.size());
    }
    @ParameterizedTest @ValueSource(strings = {"DIRECT_BUY", "DIRECT_SELL", "LIMIT_BUY", "LIMIT_SELL", "CANCEL_BUY", "CANCEL_SELL"})
    void saturatedNotificationPoolDoesNotFailFinancialOperationOrHttpResponse(String scenario) throws Exception {
        long id = prepare(scenario); var executor = pool("notificationExecutor");
        assertEquals(2, executor.getCorePoolSize()); assertEquals(4, executor.getMaxPoolSize()); assertEquals(20, executor.getQueueCapacity());
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        try {
            Runnable blocker = () -> {
                entered.countDown(); try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Saturation barrier timed out"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            };
            // Reused pools can briefly reject during queue pickup even with idle non-core workers.
            // Retry only fixture filler submissions; financial-operation rejection is never suppressed here.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (int accepted = 0; accepted < 24;) {
                try { executor.execute(blocker); accepted++; }
                catch (org.springframework.core.task.TaskRejectedException notYetPickedUp) {
                    assertTrue(System.nanoTime() < deadline, "Could not establish the saturation fixture");
                    Thread.sleep(10);
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS)); assertEquals(20, executor.getThreadPoolExecutor().getQueue().size());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new PortfolioController(trades, mock(PortfolioSnapshotService.class)), new InvestmentOrderController(orders))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            var principal = new UsernamePasswordAuthenticationToken(email, "test");
            if (scenario.startsWith("DIRECT")) {
                mvc.perform(post("/portfolio/" + (scenario.endsWith("BUY") ? "buy" : "sell")).principal(principal)
                                .contentType("application/json").content("{\"assetId\":" + assetId + ",\"quantity\":2}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.transactionStatus").value("COMPLETED"))
                        .andExpect(jsonPath("$.transactionType").value(scenario.endsWith("BUY") ? "BUY" : "SELL"));
            } else if (scenario.startsWith("CANCEL")) {
                mvc.perform(patch("/orders/" + id + "/cancel").principal(principal)).andExpect(status().isOk())
                        .andExpect(content().string("Order is successfully cancelled"));
            } else { assertDoesNotThrow(() -> execution.executeSingleOrder(id)); }
            assertEquals(1, probe.events.size()); verifyNoInteractions(transport);
            if (scenario.startsWith("CANCEL")) {
                assertEquals(OrderStatus.CANCELLED, read(() -> em.find(InvestmentOrder.class, id).getStatus())); assertEquals(0, tradeCount()); amount("1000", cash());
            } else {
                assertEquals(1, tradeCount()); amount(scenario.endsWith("BUY") ? "820" : "1180", cash());
                if (id != 0) assertEquals(OrderStatus.FILLED, read(() -> em.find(InvestmentOrder.class, id).getStatus()));
            }
        } finally { release.countDown(); notificationIdle(); }
        // Default AbortPolicy drops the rejected notification; there must be no delayed duplicate.
        verifyNoInteractions(transport);
    }
}
