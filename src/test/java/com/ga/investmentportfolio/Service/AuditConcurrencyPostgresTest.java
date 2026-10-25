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
import java.time.LocalDateTime;
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
 * Real audit service and repository; only the SSE transport boundary is mocked. No background schedulers.
 */
@EnabledIfEnvironmentVariable(named = "ORDER_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class AuditConcurrencyPostgresTest {
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
        schema = "audit_review_" + UUID.randomUUID().toString().replace("-", "");
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
            TradeExecutionService.class, AuditLogService.class, InvestmentNotificationListener.class, EventProbe.class})
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
        @Bean NotificationService notificationService() { return mock(NotificationService.class); }
    }
    @BeforeEach void fixture() {
        em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        trades = context.getBean(AssetService.class); orders = context.getBean(InvestmentOrderService.class);
        execution = context.getBean(OrderExecutionService.class); transport = context.getBean(NotificationService.class);
        probe = context.getBean(EventProbe.class); probe.events.clear(); deliveries.clear(); reset(transport);
        tx.executeWithoutResult(status -> {
            for (String entity : List.of("AuditLog", "PriceHistory", "Transaction", "InvestmentOrder", "Holding", "Portfolio", "User", "Asset"))
                em.createQuery("delete from " + entity).executeUpdate();
            Asset asset = new Asset(); asset.setSymbol("REVIEW"); asset.setName("Review"); asset.setAssetType(AssetType.STOCK);
            asset.setAssetStatus(AssetStatus.ACTIVE); asset.setCurrentPrice(bd("90")); em.persist(asset); assetId = asset.getId();
            User user = new User(); email = "audit-" + UUID.randomUUID() + "@example.test";
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
    long place(TransactionType type) {
        CreateOrderRequest request = new CreateOrderRequest(); ReflectionTestUtils.setField(request, "assetId", assetId);
        ReflectionTestUtils.setField(request, "orderType", type); ReflectionTestUtils.setField(request, "quantity", bd("2"));
        ReflectionTestUtils.setField(request, "limitPrice", bd(type == TransactionType.BUY ? "100" : "80"));
        orders.placeLimitOrder(email, request);
        return read(() -> em.createQuery("select max(o.id) from InvestmentOrder o", Long.class).getSingleResult());
    }
    long prepare(String scenario) {
        return scenario.startsWith("PLACE") ? 0 : place(scenario.endsWith("BUY") ? TransactionType.BUY : TransactionType.SELL);
    }
    long operate(String scenario, long id) {
        if (scenario.startsWith("PLACE")) return place(scenario.endsWith("BUY") ? TransactionType.BUY : TransactionType.SELL);
        if (scenario.startsWith("CANCEL")) orders.cancelOrder(email, id); else execution.executeSingleOrder(id);
        return id;
    }
    AuditAction action(String scenario) {
        return scenario.startsWith("PLACE") ? AuditAction.PLACE_LIMIT_ORDER : scenario.startsWith("CANCEL") ? AuditAction.CANCEL_LIMIT_ORDER : AuditAction.EXECUTE_LIMIT_ORDER;
    }
    List<AuditLog> audits() { return read(() -> em.createQuery("from AuditLog a order by a.id", AuditLog.class).getResultList()); }
    long auditCount() { return read(() -> em.createQuery("select count(a) from AuditLog a", Long.class).getSingleResult()); }
    record FinancialState(BigDecimal cash, BigDecimal reserved, BigDecimal quantity, BigDecimal reservedShares, long orders, long trades) {}
    FinancialState state() {
        return read(() -> {
            Portfolio p = em.find(Portfolio.class, portfolioId);
            Holding h = em.createQuery("from Holding", Holding.class).getSingleResult();
            return new FinancialState(p.getCashBalance(), p.getReservedCash(), h.getQuantity(), h.getReservedQuantity(),
                    em.createQuery("select count(o) from InvestmentOrder o", Long.class).getSingleResult(),
                    em.createQuery("select count(t) from Transaction t", Long.class).getSingleResult());
        });
    }
    void assertAudit(AuditAction action, long id, String details, LocalDateTime start, LocalDateTime end) {
        List<AuditLog> matching = audits().stream().filter(log -> log.getAction() == action).toList();
        assertEquals(1, matching.size(), "Expected exactly one " + action + " audit");
        AuditLog log = matching.get(0);
        assertNotNull(log.getId()); assertEquals(email, log.getUserEmail()); assertEquals(details, log.getDetails());
        assertTrue(log.getDetails().contains("#" + id + " for "), "Audit must identify the correct order");
        assertNotNull(log.getCreatedAt());
        assertFalse(log.getCreatedAt().isBefore(start.minusSeconds(1))); assertFalse(log.getCreatedAt().isAfter(end.plusSeconds(1)));
        read(() -> {
            Object[] versions = (Object[]) em.createNativeQuery(
                    "select a.xmin::text, o.xmin::text from audit_logs a cross join investment_orders o where a.id = :audit and o.id = :order")
                    .setParameter("audit", log.getId()).setParameter("order", id).getSingleResult();
            assertEquals(versions[0], versions[1], "Audit and order must share the same PostgreSQL transaction");
            return null;
        });
    }
    void assertPending(long id) {
        if (id != 0) read(() -> {
            InvestmentOrder order = em.find(InvestmentOrder.class, id);
            assertEquals(OrderStatus.PENDING, order.getStatus()); assertNull(order.getExecutedAt()); return null;
        });
    }
    void noNotifications() { notificationIdle(); verifyNoInteractions(transport); assertTrue(deliveries.isEmpty()); }
    void sql(String statement) { tx.executeWithoutResult(status -> em.createNativeQuery(statement).executeUpdate()); }

    @ParameterizedTest @ValueSource(strings = {"BUY", "SELL"})
    void placementWritesExactlyOneCompleteAudit(String type) {
        LocalDateTime start = LocalDateTime.now(); long id = place(TransactionType.valueOf(type)); LocalDateTime end = LocalDateTime.now();
        assertEquals(1, auditCount());
        assertAudit(AuditAction.PLACE_LIMIT_ORDER, id, "Placed " + type + " limit order #" + id + " for 2 shares of REVIEW", start, end);
        assertPending(id); noNotifications();
    }
    @ParameterizedTest @ValueSource(strings = {"BUY", "SELL"})
    void cancellationWritesExactlyOneCompleteAuditAndCannotRepeat(String type) {
        long id = place(TransactionType.valueOf(type)); LocalDateTime start = LocalDateTime.now();
        orders.cancelOrder(email, id); LocalDateTime end = LocalDateTime.now();
        assertEquals(2, auditCount());
        assertAudit(AuditAction.CANCEL_LIMIT_ORDER, id, "Cancelled limit order #" + id + " for 2.0000 shares of REVIEW", start, end);
        assertThrows(com.ga.investmentportfolio.Exception.BusinessRuleException.class, () -> orders.cancelOrder(email, id));
        assertEquals(2, auditCount());
        awaitCondition(() -> deliveries.size() == 1, "Cancellation notification missing"); notificationIdle(); assertEquals(1, deliveries.size());
        amount("1000", cash()); assertEquals(0, tradeCount());
    }
    @ParameterizedTest @ValueSource(strings = {"BUY", "SELL"})
    void schedulerExecutionWritesExactlyOneCompleteAuditAndCannotRepeat(String type) {
        long id = place(TransactionType.valueOf(type)); LocalDateTime start = LocalDateTime.now();
        orders.executePendingOrders(); orderIdle(); LocalDateTime end = LocalDateTime.now();
        assertEquals(2, auditCount());
        assertAudit(AuditAction.EXECUTE_LIMIT_ORDER, id, "Executed " + type + " limit order #" + id + " for 2.0000 shares of REVIEW", start, end);
        assertEquals(OrderStatus.FILLED, read(() -> em.find(InvestmentOrder.class, id).getStatus()));
        orders.executePendingOrders(); orderIdle(); execution.executeSingleOrder(id);
        assertEquals(2, auditCount()); assertEquals(1, tradeCount());
        amount(type.equals("BUY") ? "820" : "1180", cash());
        awaitCondition(() -> deliveries.size() == 1, "Execution notification missing"); notificationIdle(); assertEquals(1, deliveries.size());
    }
    @ParameterizedTest @ValueSource(strings = {"PLACE_BUY", "PLACE_SELL", "CANCEL_BUY", "CANCEL_SELL", "EXECUTE_BUY", "EXECUTE_SELL"})
    void explicitRollbackRemovesNewAuditAndFinancialChanges(String scenario) {
        long id = prepare(scenario); long before = auditCount(); FinancialState state = state();
        tx.executeWithoutResult(status -> {
            operate(scenario, id); assertEquals(before + 1, auditCount(), "Audit must exist before rollback"); status.setRollbackOnly();
        });
        assertEquals(before, auditCount()); assertEquals(state, state()); assertPending(id); noNotifications();
    }
    @ParameterizedTest @ValueSource(strings = {"PLACE_BUY", "PLACE_SELL", "CANCEL_BUY", "CANCEL_SELL", "EXECUTE_BUY", "EXECUTE_SELL"})
    void databaseFailureRollsBackSuccessfulAuditAlongsideFinancialChanges(String scenario) {
        long id = prepare(scenario); long before = auditCount(); FinancialState state = state();
        String table, check;
        if (scenario.startsWith("CANCEL")) { table = "investment_orders"; check = "status <> 'CANCELLED'"; }
        else if (scenario.startsWith("PLACE") && scenario.endsWith("SELL")) { table = "holdings"; check = "reservedQuantity <= 0"; }
        else if (scenario.startsWith("PLACE")) { table = "portfolios"; check = "reservedCash <= 0"; }
        else { table = "portfolios"; check = "cashBalance " + (scenario.endsWith("BUY") ? ">=" : "<=") + " 1000"; }
        sql("ALTER TABLE " + table + " ADD CONSTRAINT reject_audited_operation CHECK (" + check + ")");
        try {
            assertThrows(RuntimeException.class, () -> operate(scenario, id));
            assertEquals(before, auditCount()); assertEquals(state, state()); assertPending(id); noNotifications();
        } finally { sql("ALTER TABLE " + table + " DROP CONSTRAINT reject_audited_operation"); }
    }
    @ParameterizedTest @ValueSource(strings = {"PLACE_BUY", "PLACE_SELL", "CANCEL_BUY", "CANCEL_SELL", "EXECUTE_BUY", "EXECUTE_SELL"})
    void auditInsertFailureRollsBackOrderAndDoesNotSendSuccessNotification(String scenario) {
        long id = prepare(scenario); long before = auditCount(); FinancialState state = state();
        sql("ALTER TABLE audit_logs ADD CONSTRAINT reject_audit CHECK (action <> '" + action(scenario) + "')");
        try {
            assertThrows(RuntimeException.class, () -> operate(scenario, id));
            assertEquals(before, auditCount()); assertEquals(state, state()); assertPending(id); noNotifications();
        } finally { sql("ALTER TABLE audit_logs DROP CONSTRAINT reject_audit"); }
    }
    @ParameterizedTest @ValueSource(strings = {"PLACE_BUY", "PLACE_SELL", "CANCEL_BUY", "CANCEL_SELL", "EXECUTE_BUY", "EXECUTE_SELL"})
    void rejectedBusinessOperationCreatesNoSuccessAudit(String scenario) {
        long id = prepare(scenario);
        if (!scenario.startsWith("PLACE")) tx.executeWithoutResult(status -> {
            if (scenario.endsWith("BUY")) em.find(Portfolio.class, portfolioId).setReservedCash(bd("199.99"));
            else em.createQuery("from Holding", Holding.class).getSingleResult().setReservedQuantity(bd("1.9999"));
        });
        long before = auditCount(); FinancialState state = state();
        assertThrows(com.ga.investmentportfolio.Exception.BusinessRuleException.class, () -> {
            if (scenario.startsWith("PLACE")) {
                CreateOrderRequest request = new CreateOrderRequest(); ReflectionTestUtils.setField(request, "assetId", assetId);
                ReflectionTestUtils.setField(request, "orderType", scenario.endsWith("BUY") ? TransactionType.BUY : TransactionType.SELL);
                ReflectionTestUtils.setField(request, "quantity", bd("20")); ReflectionTestUtils.setField(request, "limitPrice", bd("100"));
                orders.placeLimitOrder(email, request);
            } else operate(scenario, id);
        });
        assertEquals(before, auditCount()); assertEquals(state, state()); assertPending(id); noNotifications();
    }
    List<Throwable> race(Runnable first, Runnable second) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable action : List.of(first, second)) futures.add(workers.submit(() -> {
                ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                try { action.run(); return null; } catch (Throwable failure) { return failure; }
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown(); List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : futures) { Throwable failure = future.get(15, TimeUnit.SECONDS); if (failure != null) failures.add(failure); }
            return failures;
        } finally { start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS)); }
    }
    @ParameterizedTest @ValueSource(strings = {"BUY", "SELL"})
    void concurrentExecutionsCreateOnlyOneExecutionAuditAndOneTrade(String type) throws Exception {
        long id = place(TransactionType.valueOf(type));
        assertEquals(List.of(), race(() -> execution.executeSingleOrder(id), () -> execution.executeSingleOrder(id)));
        assertEquals(2, auditCount()); assertEquals(1, audits().stream().filter(log -> log.getAction() == AuditAction.EXECUTE_LIMIT_ORDER).count());
        assertEquals(1, tradeCount()); amount(type.equals("BUY") ? "820" : "1180", cash());
        awaitCondition(() -> deliveries.size() == 1, "Exactly one execution notification expected"); notificationIdle(); assertEquals(1, deliveries.size());
    }
    @ParameterizedTest @ValueSource(strings = {"BUY", "SELL"})
    void cancellationExecutionRaceAuditsOnlyWinningTerminalOperation(String type) throws Exception {
        long id = place(TransactionType.valueOf(type));
        List<Throwable> failures = race(() -> orders.cancelOrder(email, id), () -> execution.executeSingleOrder(id));
        assertTrue(failures.size() <= 1, failures.toString()); failures.forEach(failure -> assertInstanceOf(com.ga.investmentportfolio.Exception.BusinessRuleException.class, failure));
        OrderStatus result = read(() -> em.find(InvestmentOrder.class, id).getStatus());
        assertTrue(result == OrderStatus.FILLED || result == OrderStatus.CANCELLED);
        AuditAction expected = result == OrderStatus.FILLED ? AuditAction.EXECUTE_LIMIT_ORDER : AuditAction.CANCEL_LIMIT_ORDER;
        assertEquals(2, auditCount()); assertEquals(1, audits().stream().filter(log -> log.getAction() == expected).count());
        assertEquals(0, audits().stream().filter(log -> log.getAction() != expected && log.getAction() != AuditAction.PLACE_LIMIT_ORDER).count());
        assertEquals(result == OrderStatus.FILLED ? 1 : 0, tradeCount());
        awaitCondition(() -> deliveries.size() == 1, "Exactly one winning-operation notification expected"); notificationIdle(); assertEquals(1, deliveries.size());
    }
    @Test void concurrentDifferentOrdersKeepDistinctOrderIdsAndCorrectAuditCounts() throws Exception {
        long first = place(TransactionType.BUY), second = place(TransactionType.BUY);
        assertEquals(List.of(), race(() -> execution.executeSingleOrder(first), () -> execution.executeSingleOrder(second)));
        assertEquals(4, auditCount()); assertEquals(2, tradeCount()); amount("640", cash());
        for (long id : new long[]{first, second}) assertEquals(2, audits().stream().filter(log -> log.getDetails().contains("#" + id + " for ")).count());
        awaitCondition(() -> deliveries.size() == 2, "Two committed executions must notify"); notificationIdle(); assertEquals(2, deliveries.size());
    }
    @ParameterizedTest @ValueSource(strings = {"BUY", "SELL"})
    void unmetLimitDoesNotCreateExecutionAudit(String type) {
        long id = place(TransactionType.valueOf(type));
        tx.executeWithoutResult(status -> em.find(Asset.class, assetId).setCurrentPrice(bd(type.equals("BUY") ? "110" : "70")));
        FinancialState state = state(); orders.executePendingOrders(); orderIdle();
        assertEquals(1, auditCount()); assertPending(id); assertEquals(state, state()); noNotifications();
    }
}
