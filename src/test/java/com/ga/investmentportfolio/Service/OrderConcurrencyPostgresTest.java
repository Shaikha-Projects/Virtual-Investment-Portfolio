package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.*;
import com.ga.investmentportfolio.Config.ThreadPoolConfig;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.ga.investmentportfolio.Enums.*;
import com.ga.investmentportfolio.Enums.Role;
import com.ga.investmentportfolio.Exception.BusinessRuleException;
import com.ga.investmentportfolio.Model.*;
import com.ga.investmentportfolio.Repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL, Spring transaction proxies and real repositories; no H2 fallback.
 * An isolated random schema is created/dropped, never application tables.
 * Scheduler registration is deliberately absent: tests invoke the actual scheduled
 * method deterministically, without a background timer racing with assertions.
 */
@EnabledIfEnvironmentVariable(named = "ORDER_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class OrderConcurrencyPostgresTest {
    static AnnotationConfigApplicationContext context;
    static String schema;
    InvestmentOrderService orders;
    OrderExecutionService execution;
    AssetService trades;
    TransactionTemplate tx;
    EntityManager em;
    long portfolioId, assetId;
    String email;

    @BeforeAll static void start() throws Exception {
        schema = "order_review_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("ORDER_TEST_JDBC_URL"), user(), password());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        context = new AnnotationConfigApplicationContext(Config.class);
    }

    @AfterAll static void stop() throws Exception {
        if (context != null) context.close();
        if (schema != null) try (var connection = DriverManager.getConnection(System.getenv("ORDER_TEST_JDBC_URL"), user(), password());
                                 var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    static String user() { return Optional.ofNullable(System.getenv("ORDER_TEST_DB_USER")).orElse("postgres"); }
    static String password() { return Optional.ofNullable(System.getenv("ORDER_TEST_DB_PASSWORD")).orElse(""); }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = InvestmentOrderRepository.class)
    @Import({InvestmentOrderService.class, OrderExecutionService.class, TradeExecutionService.class, AssetService.class, ThreadPoolConfig.class})
    static class Config {
        @Bean DataSource dataSource() {
            String url = System.getenv("ORDER_TEST_JDBC_URL");
            return new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + "&ApplicationName=" + schema, user(), password());
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(User.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.connection.isolation", "2", "hibernate.show_sql", "false"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean AuditLogService auditLogService() { return mock(AuditLogService.class); }
        @Bean NotificationService notificationService() { return mock(NotificationService.class); }
    }

    @BeforeEach void fixture() {
        orders = context.getBean(InvestmentOrderService.class);
        execution = context.getBean(OrderExecutionService.class);
        trades = context.getBean(AssetService.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        tx.executeWithoutResult(status -> {
            // All fixtures belong to this isolated schema; clean between cases.
            for (String entity : List.of("Transaction", "InvestmentOrder", "Holding", "Portfolio", "User", "Asset"))
                em.createQuery("delete from " + entity).executeUpdate();
            User user = new User();
            email = "review-" + UUID.randomUUID() + "@example.test";
            user.setEmailAddress(email); user.setFirstName("Review"); user.setLastName("Test");
            user.setPhoneNumber("0000000000"); user.setPassword("test-only-placeholder");
            user.setRole(Role.USER); user.setStatus(UserStatus.ACTIVE); em.persist(user);
            Portfolio portfolio = new Portfolio(); portfolio.setUser(user); portfolio.setCashBalance(bd("1000"));
            em.persist(portfolio); user.setPortfolio(portfolio); portfolioId = portfolio.getId();
            Asset asset = new Asset(); asset.setSymbol("REVIEW"); asset.setName("Review asset");
            asset.setAssetType(AssetType.STOCK); asset.setAssetStatus(AssetStatus.ACTIVE); asset.setCurrentPrice(bd("90"));
            em.persist(asset); assetId = asset.getId();
        });
    }

    static BigDecimal bd(String value) { return new BigDecimal(value); }
    static void amount(String expected, BigDecimal actual) { assertEquals(0, bd(expected).compareTo(actual), () -> "Expected " + expected + ", got " + actual); }
    <T> T read(Supplier<T> action) { return tx.execute(status -> action.get()); }
    Portfolio portfolio() { return em.find(Portfolio.class, portfolioId); }
    Holding holding() { return em.createQuery("from Holding h where h.portfolio.id = :id", Holding.class).setParameter("id", portfolioId).getSingleResult(); }
    InvestmentOrder order(long id) { return em.find(InvestmentOrder.class, id); }
    long transactionCount() { return read(() -> em.createQuery("select count(t) from Transaction t", Long.class).getSingleResult()); }
    long place(TransactionType type, String quantity, String limit) {
        CreateOrderRequest request = new CreateOrderRequest();
        ReflectionTestUtils.setField(request, "assetId", assetId);
        ReflectionTestUtils.setField(request, "orderType", type);
        ReflectionTestUtils.setField(request, "quantity", bd(quantity));
        ReflectionTestUtils.setField(request, "limitPrice", bd(limit));
        orders.placeLimitOrder(email, request);
        return read(() -> em.createQuery("select max(o.id) from InvestmentOrder o", Long.class).getSingleResult());
    }
    void holding(String quantity, String average) {
        tx.executeWithoutResult(status -> {
            Holding h = new Holding(); h.setPortfolio(portfolio()); h.setAsset(em.find(Asset.class, assetId));
            h.setQuantity(bd(quantity)); h.setAverageBuyPrice(bd(average)); em.persist(h);
        });
    }
    void buy(String quantity) {
        BuyAssetRequest request = new BuyAssetRequest();
        ReflectionTestUtils.setField(request, "assetId", assetId); ReflectionTestUtils.setField(request, "quantity", bd(quantity));
        trades.buyAsset(email, request);
    }
    void sell(String quantity) {
        SellAssetRequest request = new SellAssetRequest();
        ReflectionTestUtils.setField(request, "assetId", assetId); ReflectionTestUtils.setField(request, "quantity", bd(quantity));
        trades.sellAsset(email, request);
    }

    /** Independent threads => independent persistence contexts/connections.
     * Bounded waits fail a test instead of hanging on a deadlock. */
    List<Throwable> race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable action : List.of(first, second)) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                try { action.run(); return null; } catch (Throwable failure) { return failure; }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (var future : futures) { Throwable failure = future.get(15, TimeUnit.SECONDS); if (failure != null) failures.add(failure); }
            return failures;
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS)); }
    }

    ThreadPoolTaskExecutor pool() { return context.getBean("orderExecutor", ThreadPoolTaskExecutor.class); }

    void awaitCondition(java.util.function.BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            try { Thread.sleep(10); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }

    void poolIdle() {
        // A fresh core worker owns its first task before getActiveCount() observes it.
        // The service registers every accepted task before submission, covering that gap.
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(orders);
        Set<?> processing = (Set<?>) ReflectionTestUtils.getField(target, "processingOrderIds");
        assertNotNull(processing);
        awaitCondition(() -> processing.isEmpty() && pool().getActiveCount() == 0 && pool().getThreadPoolExecutor().getQueue().isEmpty(),
                "Order pool did not finish its tasks");
    }

    void dispatchAndWait() { orders.executePendingOrders(); poolIdle(); }

    @AfterEach void drainWorkers() { poolIdle(); }

    // ApplicationName identifies only connections in this test's random schema.
    long waitingWorkers(String table) {
        // Observe on a fresh connection to avoid PostgreSQL's transaction-scoped statistics snapshot.
        try (var connection = DriverManager.getConnection(System.getenv("ORDER_TEST_JDBC_URL"), user(), password());
             var statement = connection.prepareStatement(
                     "select count(*) from pg_stat_activity where application_name = ? " +
                     "and wait_event_type = 'Lock' and query like ?")) {
            statement.setString(1, schema); statement.setString(2, "%" + table + "%");
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
    }

    @Test void automaticBuyReleasesFullReservationAndUsesActualMarketPrice() {
        holding("2", "50");
        long id = place(TransactionType.BUY, "3", "100");
        dispatchAndWait();
        read(() -> { amount("730", portfolio().getCashBalance()); amount("0", portfolio().getReservedCash());
            amount("5", holding().getQuantity()); amount("74", holding().getAverageBuyPrice());
            assertEquals(OrderStatus.FILLED, order(id).getStatus()); assertNotNull(order(id).getExecutedAt()); return null; });
        assertEquals(1, transactionCount());
        read(() -> { var t = em.createQuery("from Transaction", Transaction.class).getSingleResult();
            amount("90", t.getPricePerUnit()); amount("270", t.getTotalAmount()); assertEquals(TransactionType.BUY, t.getTransactionType()); return null; });
    }

    @Test void automaticSellReleasesOnlyItsReservation() {
        holding("10", "50");
        long id = place(TransactionType.SELL, "3", "80");
        place(TransactionType.SELL, "2", "100"); // remains pending; keep its shares reserved
        dispatchAndWait();
        read(() -> { amount("1270", portfolio().getCashBalance()); amount("7", holding().getQuantity());
            amount("2", holding().getReservedQuantity()); amount("50", holding().getAverageBuyPrice());
            assertEquals(OrderStatus.FILLED, order(id).getStatus()); return null; });
        assertEquals(1, transactionCount());
    }

    @Test void unmetBuyAndSellConditionsStayPendingWithoutFinancialChanges() {
        holding("10", "50");
        long buy = place(TransactionType.BUY, "2", "80"), sell = place(TransactionType.SELL, "3", "100");
        dispatchAndWait();
        read(() -> { amount("1000", portfolio().getCashBalance()); amount("160", portfolio().getReservedCash());
            amount("10", holding().getQuantity()); amount("3", holding().getReservedQuantity());
            for (long id : new long[]{buy, sell}) { assertEquals(OrderStatus.PENDING, order(id).getStatus()); assertNull(order(id).getExecutedAt()); } return null; });
        assertEquals(0, transactionCount());
    }

    @Test void twoConcurrentBuysCannotOverspendOrCreateDuplicateHoldings() throws Exception {
        List<Throwable> failures = race(() -> buy("7"), () -> buy("7"));
        assertEquals(1, failures.size(), failures.toString()); assertInstanceOf(BusinessRuleException.class, failures.get(0));
        read(() -> { amount("370", portfolio().getCashBalance()); amount("7", holding().getQuantity()); return null; });
        assertEquals(1, transactionCount());
    }

    @Test void twoAffordableConcurrentBuysCreateOneHoldingWithBothPurchases() throws Exception {
        assertEquals(List.of(), race(() -> buy("2"), () -> buy("3")));
        read(() -> { assertAll(() -> amount("550", portfolio().getCashBalance()),
                () -> amount("5", holding().getQuantity()), () -> amount("90", holding().getAverageBuyPrice()),
                () -> assertEquals(1L, em.createQuery("select count(h) from Holding h", Long.class).getSingleResult()),
                () -> assertEquals(2L, em.createQuery("select count(t) from Transaction t", Long.class).getSingleResult())); return null; });
    }

    @Test void twoConcurrentSellsCannotOversellOrLoseCashUpdates() throws Exception {
        holding("10", "50");
        List<Throwable> failures = race(() -> sell("7"), () -> sell("7"));
        assertEquals(1, failures.size(), failures.toString()); assertInstanceOf(BusinessRuleException.class, failures.get(0));
        read(() -> { amount("1630", portfolio().getCashBalance()); amount("3", holding().getQuantity()); return null; });
        assertEquals(1, transactionCount());
    }

    @Test void simultaneousBuyAndSellPreserveBothCashUpdates() throws Exception {
        holding("10", "50");
        assertEquals(List.of(), race(() -> buy("2"), () -> sell("3")));
        read(() -> { amount("1090", portfolio().getCashBalance()); amount("9", holding().getQuantity()); return null; });
        assertEquals(2, transactionCount());
    }

    @Test void twoAffordableConcurrentSellsMustCreditBothProceeds() throws Exception {
        holding("10", "50");
        assertEquals(List.of(), race(() -> sell("2"), () -> sell("3")));
        read(() -> { amount("1450", portfolio().getCashBalance()); amount("5", holding().getQuantity()); return null; });
        assertEquals(2, transactionCount());
    }

    @Test void twoExecutionAttemptsFillOrderExactlyOnce() throws Exception {
        long id = place(TransactionType.BUY, "3", "100");
        assertEquals(List.of(), race(() -> execution.executeSingleOrder(id), () -> execution.executeSingleOrder(id)));
        read(() -> { amount("730", portfolio().getCashBalance()); amount("0", portfolio().getReservedCash());
            amount("3", holding().getQuantity()); assertEquals(OrderStatus.FILLED, order(id).getStatus()); return null; });
        assertEquals(1, transactionCount());
    }

    @Test void twoSellExecutionAttemptsDeleteFinalHoldingExactlyOnce() throws Exception {
        holding("3", "50");
        long id = place(TransactionType.SELL, "3", "80");
        assertEquals(List.of(), race(() -> execution.executeSingleOrder(id), () -> execution.executeSingleOrder(id)));
        read(() -> { amount("1270", portfolio().getCashBalance());
            assertEquals(0L, em.createQuery("select count(h) from Holding h", Long.class).getSingleResult());
            assertEquals(OrderStatus.FILLED, order(id).getStatus()); return null; });
        assertEquals(1, transactionCount());
    }

    @Test void concurrentDifferentOrdersPreserveCashAndReleaseBothReservations() throws Exception {
        long first = place(TransactionType.BUY, "2", "100"), second = place(TransactionType.BUY, "3", "100");
        assertEquals(List.of(), race(() -> execution.executeSingleOrder(first), () -> execution.executeSingleOrder(second)));
        read(() -> { amount("550", portfolio().getCashBalance()); amount("0", portfolio().getReservedCash());
            amount("5", holding().getQuantity()); return null; });
        assertEquals(2, transactionCount());
    }

    @Test void concurrentBuyReservationsCannotExceedCash() throws Exception {
        CyclicBarrier loaded = new CyclicBarrier(2);
        Runnable placeWithPreloadedPortfolio = () -> tx.executeWithoutResult(status -> {
            amount("1000", context.getBean(UserRepository.class).findByEmailAddress(email).orElseThrow().getPortfolio().getCashBalance());
            try { loaded.await(5, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            place(TransactionType.BUY, "7", "100");
        });
        List<Throwable> failures = race(placeWithPreloadedPortfolio, placeWithPreloadedPortfolio);
        dispatchAndWait();
        read(() -> { assertAll(
                () -> assertEquals(1, failures.size(), failures.toString()),
                () -> assertTrue(portfolio().getCashBalance().signum() >= 0, "Negative cash: " + portfolio().getCashBalance()),
                () -> assertTrue(portfolio().getReservedCash().signum() >= 0, "Negative reservation: " + portfolio().getReservedCash()),
                () -> assertTrue(portfolio().getCashBalance().subtract(portfolio().getReservedCash()).signum() >= 0, "Negative available cash")); return null; });
    }

    @Test void preloadedPortfolioMustBeFreshAfterLockAcquisition() throws Exception {
        // Deterministically models two persistence contexts that loaded the inverse
        // User.portfolio association before either acquired its write lock.
        CyclicBarrier loaded = new CyclicBarrier(2);
        Runnable buyWithPreloadedPortfolio = () -> tx.executeWithoutResult(status -> {
            amount("1000", context.getBean(UserRepository.class).findByEmailAddress(email).orElseThrow().getPortfolio().getCashBalance());
            try { loaded.await(5, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            buy("2");
        });
        assertEquals(List.of(), race(buyWithPreloadedPortfolio, buyWithPreloadedPortfolio));
        read(() -> { amount("640", portfolio().getCashBalance()); amount("4", holding().getQuantity()); return null; });
        assertEquals(2, transactionCount());
    }

    @Test void fractionalOrderMustReleaseReservationAndMatchRecordedCashAmount() {
        tx.executeWithoutResult(status -> em.find(Asset.class, assetId).setCurrentPrice(bd("1.00")));
        long id = place(TransactionType.BUY, "0.015", "1.00");
        dispatchAndWait();
        read(() -> { assertEquals(OrderStatus.FILLED, order(id).getStatus());
            assertAll(() -> amount("0", portfolio().getReservedCash()),
                    () -> amount("999.98", portfolio().getCashBalance()),
                    () -> amount("0.015", holding().getQuantity())); return null; });
        assertEquals(1, transactionCount());
    }

    @Test void concurrentCancellationsMustReleaseBothCashReservations() throws Exception {
        long first = place(TransactionType.BUY, "2", "100"), second = place(TransactionType.BUY, "3", "100");
        CyclicBarrier loaded = new CyclicBarrier(2);
        java.util.function.LongConsumer cancel = id -> tx.executeWithoutResult(status -> {
            amount("500", context.getBean(UserRepository.class).findByEmailAddress(email).orElseThrow().getPortfolio().getReservedCash());
            try { loaded.await(5, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            orders.cancelOrder(email, id);
        });
        assertEquals(List.of(), race(() -> cancel.accept(first), () -> cancel.accept(second)));
        read(() -> { amount("0", portfolio().getReservedCash());
            assertEquals(OrderStatus.CANCELLED, order(first).getStatus()); assertEquals(OrderStatus.CANCELLED, order(second).getStatus()); return null; });
        assertEquals(0, transactionCount());
    }

    @Test void sellCancellationAndExecutionReleaseSharesOnlyOnce() throws Exception {
        holding("10", "50");
        long id = place(TransactionType.SELL, "3", "80");
        List<Throwable> failures = race(() -> orders.cancelOrder(email, id), () -> execution.executeSingleOrder(id));
        assertTrue(failures.size() <= 1, failures.toString());
        failures.forEach(failure -> assertInstanceOf(BusinessRuleException.class, failure));
        boolean filled = read(() -> order(id).getStatus() == OrderStatus.FILLED);
        read(() -> { amount("0", holding().getReservedQuantity()); amount(filled ? "7" : "10", holding().getQuantity());
            amount(filled ? "1270" : "1000", portfolio().getCashBalance()); return null; });
        assertEquals(filled ? 1 : 0, transactionCount());
    }

    @Test void cancellationAndExecutionHaveOnlyOneWinner() throws Exception {
        long id = place(TransactionType.BUY, "3", "100");
        List<Throwable> failures = race(() -> orders.cancelOrder(email, id), () -> execution.executeSingleOrder(id));
        assertTrue(failures.size() <= 1, failures.toString());
        failures.forEach(failure -> assertInstanceOf(BusinessRuleException.class, failure));
        OrderStatus result = read(() -> order(id).getStatus());
        assertTrue(result == OrderStatus.FILLED || result == OrderStatus.CANCELLED);
        read(() -> { amount("0", portfolio().getReservedCash()); amount(result == OrderStatus.FILLED ? "730" : "1000", portfolio().getCashBalance()); return null; });
        assertEquals(result == OrderStatus.FILLED ? 1 : 0, transactionCount());
    }

    @Test void cancellationReleasesBuyCashAndSellSharesExactlyOnce() {
        holding("10", "50");
        long buy = place(TransactionType.BUY, "2", "80"), sell = place(TransactionType.SELL, "3", "100");
        orders.cancelOrder(email, buy); orders.cancelOrder(email, sell);
        assertThrows(BusinessRuleException.class, () -> orders.cancelOrder(email, buy));
        read(() -> { amount("1000", portfolio().getCashBalance()); amount("0", portfolio().getReservedCash());
            amount("10", holding().getQuantity()); amount("0", holding().getReservedQuantity()); return null; });
        assertEquals(0, transactionCount());
    }

    @Test void transactionInsertFailureRollsBackCashHoldingReservationsAndOrder() {
        long id = place(TransactionType.BUY, "3", "100");
        // Real PostgreSQL constraint failure after cash and holding mutations.
        tx.executeWithoutResult(status -> em.createNativeQuery("ALTER TABLE transactions ADD CONSTRAINT review_reject_trade CHECK (quantity < 0)").executeUpdate());
        try {
            assertThrows(RuntimeException.class, () -> execution.executeSingleOrder(id));
            read(() -> { amount("1000", portfolio().getCashBalance()); amount("300", portfolio().getReservedCash());
                assertEquals(OrderStatus.PENDING, order(id).getStatus()); assertNull(order(id).getExecutedAt());
                assertEquals(0L, em.createQuery("select count(h) from Holding h", Long.class).getSingleResult()); return null; });
            assertEquals(0, transactionCount());
        } finally {
            tx.executeWithoutResult(status -> em.createNativeQuery("ALTER TABLE transactions DROP CONSTRAINT review_reject_trade").executeUpdate());
        }
        execution.executeSingleOrder(id); assertEquals(1, transactionCount());
    }

    void assertRejectedOrderUnchanged(long id, String cash, String reserved, String shares, String reservedShares) {
        read(() -> {
            amount(cash, portfolio().getCashBalance()); amount(reserved, portfolio().getReservedCash());
            amount(shares, holding().getQuantity()); amount(reservedShares, holding().getReservedQuantity());
            amount("50", holding().getAverageBuyPrice());
            assertEquals(OrderStatus.PENDING, order(id).getStatus()); assertNull(order(id).getExecutedAt());
            return null;
        });
        assertEquals(0, transactionCount());
    }

    @Test void buyExecutionRejectsInsufficientReservedCashWithoutChanges() {
        holding("10", "50");
        long id = place(TransactionType.BUY, "3", "100");
        tx.executeWithoutResult(status -> portfolio().setReservedCash(bd("299.99")));
        var failure = assertThrows(BusinessRuleException.class, () -> execution.executeSingleOrder(id));
        assertEquals("Insufficient reserved cash", failure.getMessage());
        assertRejectedOrderUnchanged(id, "1000", "299.99", "10", "0");
    }

    @Test void buyExecutionRejectsInsufficientCashBalanceWithoutChanges() {
        holding("10", "50");
        long id = place(TransactionType.BUY, "3", "100");
        tx.executeWithoutResult(status -> portfolio().setCashBalance(bd("269.99")));
        var failure = assertThrows(BusinessRuleException.class, () -> execution.executeSingleOrder(id));
        assertEquals("Insufficient cash balance", failure.getMessage());
        assertRejectedOrderUnchanged(id, "269.99", "300", "10", "0");
    }

    @Test void buyCancellationRejectsInsufficientReservedCashWithoutChanges() {
        holding("10", "50");
        long id = place(TransactionType.BUY, "3", "100");
        tx.executeWithoutResult(status -> portfolio().setReservedCash(bd("299.99")));
        var failure = assertThrows(BusinessRuleException.class, () -> orders.cancelOrder(email, id));
        assertEquals("Insufficient reserved cash", failure.getMessage());
        assertRejectedOrderUnchanged(id, "1000", "299.99", "10", "0");
    }

    @Test void sellCancellationRejectsInsufficientReservedSharesWithoutChanges() {
        holding("10", "50");
        long id = place(TransactionType.SELL, "3", "80");
        tx.executeWithoutResult(status -> holding().setReservedQuantity(bd("2.9999")));
        var failure = assertThrows(BusinessRuleException.class, () -> orders.cancelOrder(email, id));
        assertEquals("Insufficient reserved shares", failure.getMessage());
        assertRejectedOrderUnchanged(id, "1000", "0", "10", "2.9999");
    }

    @Test void sellTransactionInsertFailureRollsBackDeletedHoldingAndOrder() {
        holding("3", "50");
        long id = place(TransactionType.SELL, "3", "80");
        tx.executeWithoutResult(status -> em.createNativeQuery("ALTER TABLE transactions ADD CONSTRAINT review_reject_trade CHECK (quantity < 0)").executeUpdate());
        try {
            assertThrows(RuntimeException.class, () -> execution.executeSingleOrder(id));
            assertRejectedOrderUnchanged(id, "1000", "0", "3", "3");
        } finally {
            tx.executeWithoutResult(status -> em.createNativeQuery("ALTER TABLE transactions DROP CONSTRAINT review_reject_trade").executeUpdate());
        }
        execution.executeSingleOrder(id);
        assertEquals(1, transactionCount());
        read(() -> {
            amount("1270", portfolio().getCashBalance());
            assertEquals(OrderStatus.FILLED, order(id).getStatus()); assertNotNull(order(id).getExecutedAt());
            assertEquals(0L, em.createQuery("select count(h) from Holding h", Long.class).getSingleResult());
            return null;
        });
    }

    @Test void pooledBuyOrdersWaitForPortfolioLockThenPreserveCashAndOneHolding() {
        long first = place(TransactionType.BUY, "2", "100"), second = place(TransactionType.BUY, "3", "100");
        try {
            tx.executeWithoutResult(status -> {
                context.getBean(PortfolioRepository.class).findByIdForUpdate(portfolioId).orElseThrow();
                orders.executePendingOrders();
                awaitCondition(() -> waitingWorkers("portfolios") == 2, "Two pool workers must wait for the portfolio lock");
                assertEquals(0, transactionCount());
            });
        } finally { poolIdle(); }
        read(() -> {
            amount("550", portfolio().getCashBalance()); amount("0", portfolio().getReservedCash());
            amount("5", holding().getQuantity()); amount("90", holding().getAverageBuyPrice());
            assertEquals(1L, em.createQuery("select count(h) from Holding h", Long.class).getSingleResult());
            assertEquals(OrderStatus.FILLED, order(first).getStatus()); assertEquals(OrderStatus.FILLED, order(second).getStatus());
            return null;
        });
        assertEquals(2, transactionCount());
    }

    @Test void pooledSellOrdersWaitForHoldingLockThenPreserveSharesAndBothProceeds() {
        holding("10", "50");
        long first = place(TransactionType.SELL, "2", "80"), second = place(TransactionType.SELL, "3", "80");
        try {
            tx.executeWithoutResult(status -> {
                context.getBean(HoldingRepository.class).findByPortfolioAndAsset(portfolio(), em.find(Asset.class, assetId)).orElseThrow();
                orders.executePendingOrders();
                awaitCondition(() -> waitingWorkers("holdings") == 1 && waitingWorkers("portfolios") == 1,
                        "Pool workers must wait for the holding and portfolio locks");
                assertEquals(0, transactionCount());
            });
        } finally { poolIdle(); }
        read(() -> {
            amount("1450", portfolio().getCashBalance()); amount("5", holding().getQuantity());
            amount("0", holding().getReservedQuantity()); amount("50", holding().getAverageBuyPrice());
            assertEquals(OrderStatus.FILLED, order(first).getStatus()); assertEquals(OrderStatus.FILLED, order(second).getStatus());
            return null;
        });
        assertEquals(2, transactionCount());
    }

    @Test void pooledFailedOrderRollsBackWhileAnotherOrderCommitsAndCanRetry() {
        holding("10", "50");
        long failed = place(TransactionType.BUY, "3", "100"), healthy = place(TransactionType.SELL, "2", "80");
        tx.executeWithoutResult(status -> portfolio().setReservedCash(bd("299.99")));
        dispatchAndWait();
        read(() -> {
            amount("1180", portfolio().getCashBalance()); amount("299.99", portfolio().getReservedCash());
            amount("8", holding().getQuantity()); amount("0", holding().getReservedQuantity());
            assertEquals(OrderStatus.PENDING, order(failed).getStatus()); assertNull(order(failed).getExecutedAt());
            assertEquals(OrderStatus.FILLED, order(healthy).getStatus()); assertNotNull(order(healthy).getExecutedAt());
            return null;
        });
        assertEquals(1, transactionCount());
        tx.executeWithoutResult(status -> portfolio().setReservedCash(bd("300")));
        dispatchAndWait();
        read(() -> {
            amount("910", portfolio().getCashBalance()); amount("0", portfolio().getReservedCash());
            amount("11", holding().getQuantity()); assertEquals(OrderStatus.FILLED, order(failed).getStatus());
            return null;
        });
        assertEquals(2, transactionCount());
    }

    @Test void portfolioWriteLockActuallyBlocksAnotherConnection() throws Exception {
        assertDatabaseLockBlocks(() -> context.getBean(PortfolioRepository.class).findByIdForUpdate(portfolioId).orElseThrow());
    }

    @Test void orderWriteLockActuallyBlocksAnotherConnection() throws Exception {
        long id = place(TransactionType.BUY, "3", "100");
        assertDatabaseLockBlocks(() -> context.getBean(InvestmentOrderRepository.class).findByIdForUpdate(id).orElseThrow());
    }

    @Test void holdingWriteLockActuallyBlocksAnotherConnection() throws Exception {
        holding("10", "50");
        assertDatabaseLockBlocks(() -> context.getBean(HoldingRepository.class).findByPortfolioAndAsset(portfolio(), em.find(Asset.class, assetId)).orElseThrow());
    }

    void assertDatabaseLockBlocks(Runnable acquire) throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), attempted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
                acquire.run(); locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> second = pool.submit(() -> tx.executeWithoutResult(status -> {
                attempted.countDown(); acquire.run();
            }));
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }
}
