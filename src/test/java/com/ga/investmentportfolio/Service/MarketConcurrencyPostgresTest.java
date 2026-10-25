package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Config.ThreadPoolConfig;
import com.ga.investmentportfolio.DTO.Request.CreateOrderRequest;
import com.ga.investmentportfolio.Enums.*;
import com.ga.investmentportfolio.Enums.Role;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real services, repositories, transaction proxies and both production executors.
 * Only random price generation is stubbed (+1) to make lost updates/rollback observable.
 * Audit/notification side effects are mocked. No wall-clock schedulers are registered.
 */
@EnabledIfEnvironmentVariable(named = "ORDER_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class MarketConcurrencyPostgresTest {
    static AnnotationConfigApplicationContext context;
    static String schema;
    EntityManager em;
    TransactionTemplate tx;
    MarketSimulationService market;
    MarketPriceUpdateService worker;
    InvestmentOrderService orders;
    long assetId, portfolioId;
    String email;

    static String url() { return System.getenv("ORDER_TEST_JDBC_URL"); }
    static String user() { return Optional.ofNullable(System.getenv("ORDER_TEST_DB_USER")).orElse("postgres"); }
    static String password() { return Optional.ofNullable(System.getenv("ORDER_TEST_DB_PASSWORD")).orElse(""); }
    static BigDecimal bd(String value) { return new BigDecimal(value); }
    static void amount(String expected, BigDecimal actual) { assertEquals(0, bd(expected).compareTo(actual), "Actual amount: " + actual); }

    @BeforeAll static void start() throws Exception {
        schema = "market_review_" + UUID.randomUUID().toString().replace("-", "");
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
    @Import({ThreadPoolConfig.class, MarketSimulationService.class, InvestmentOrderService.class,
            OrderExecutionService.class, TradeExecutionService.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(url() + (url().contains("?") ? "&" : "?") +
                    "currentSchema=" + schema + "&ApplicationName=" + schema, user(), password());
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource); factory.setPackagesToScan(Asset.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.show_sql", "false"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean MarketPriceUpdateService marketPriceUpdateService(AssetRepository assets, PriceHistoryRepository histories) {
            return spy(new MarketPriceUpdateService(assets, histories));
        }
        @Bean AuditLogService auditLogService() { return mock(AuditLogService.class); }
        @Bean NotificationService notificationService() { return mock(NotificationService.class); }
    }

    @BeforeEach void fixture() {
        em = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        market = context.getBean(MarketSimulationService.class);
        worker = context.getBean(MarketPriceUpdateService.class);
        orders = context.getBean(InvestmentOrderService.class);
        MarketPriceUpdateService target = AopTestUtils.getUltimateTargetObject(worker);
        reset(target);
        doAnswer(call -> call.getArgument(0, BigDecimal.class).add(BigDecimal.ONE)).when(target).calculateNewPrice(any());
        tx.executeWithoutResult(status -> {
            for (String entity : List.of("PriceHistory", "Transaction", "InvestmentOrder", "Holding", "Portfolio", "User", "Asset"))
                em.createQuery("delete from " + entity).executeUpdate();
            Asset asset = asset("ACTIVE", AssetStatus.ACTIVE); assetId = asset.getId();
            User user = new User(); email = "market-" + UUID.randomUUID() + "@example.test";
            user.setEmailAddress(email); user.setFirstName("Market"); user.setLastName("Review");
            user.setPhoneNumber("0000000000"); user.setPassword("test-only"); user.setRole(Role.USER); user.setStatus(UserStatus.ACTIVE);
            em.persist(user);
            Portfolio portfolio = new Portfolio(); portfolio.setUser(user); portfolio.setCashBalance(bd("1000"));
            em.persist(portfolio); user.setPortfolio(portfolio); portfolioId = portfolio.getId();
        });
    }

    Asset asset(String symbol, AssetStatus status) {
        Asset asset = new Asset(); asset.setSymbol(symbol); asset.setName(symbol); asset.setAssetType(AssetType.STOCK);
        asset.setAssetStatus(status); asset.setCurrentPrice(bd("90")); em.persist(asset); return asset;
    }
    <T> T read(Supplier<T> action) { return tx.execute(status -> action.get()); }
    ThreadPoolTaskExecutor pool(String name) { return context.getBean(name, ThreadPoolTaskExecutor.class); }
    void awaitCondition(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            try { Thread.sleep(10); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }
    void idle(Object service, String field, String poolName) {
        Object target = AopTestUtils.getUltimateTargetObject(service);
        Set<?> processing = (Set<?>) ReflectionTestUtils.getField(target, field);
        assertNotNull(processing);
        awaitCondition(() -> processing.isEmpty() && pool(poolName).getActiveCount() == 0 &&
                pool(poolName).getThreadPoolExecutor().getQueue().isEmpty(), poolName + " did not finish");
    }
    void marketIdle() { idle(market, "processingAssetIds", "marketExecutor"); }
    void orderIdle() { idle(orders, "processingOrderIds", "orderExecutor"); }
    @AfterEach void drain() { marketIdle(); orderIdle(); }
    void dispatch() { market.updateMarketPrices(); marketIdle(); }
    long historyCount(long id) { return read(() -> em.createQuery("select count(h) from PriceHistory h where h.asset.id = :id", Long.class).setParameter("id", id).getSingleResult()); }
    BigDecimal price(long id) { return read(() -> em.find(Asset.class, id).getCurrentPrice()); }
    List<BigDecimal> histories(long id) { return read(() -> em.createQuery("select h.price from PriceHistory h where h.asset.id = :id order by h.id", BigDecimal.class).setParameter("id", id).getResultList()); }
    long waitingWorkers() {
        try (var connection = DriverManager.getConnection(url(), user(), password());
             var statement = connection.prepareStatement("select count(*) from pg_stat_activity where application_name = ? and wait_event_type = 'Lock' and query like '%assets%'")) {
            statement.setString(1, schema);
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
        } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
    }
    void lock(long id) { context.getBean(AssetRepository.class).findByIdForUpdate(id).orElseThrow(); }
    void sql(String statement) { tx.executeWithoutResult(status -> em.createNativeQuery(statement).executeUpdate()); }

    @Test void bothProductionPoolsAreDistinctConfiguredAndInjectedByName() {
        var orderPool = pool("orderExecutor"); var marketPool = pool("marketExecutor");
        assertNotSame(orderPool, marketPool); assertNotSame(orderPool.getThreadPoolExecutor(), marketPool.getThreadPoolExecutor());
        assertEquals(3, orderPool.getCorePoolSize()); assertEquals(5, orderPool.getMaxPoolSize()); assertEquals(10, orderPool.getQueueCapacity());
        assertEquals(3, marketPool.getCorePoolSize()); assertEquals(5, marketPool.getMaxPoolSize()); assertEquals(20, marketPool.getQueueCapacity());
        assertEquals("market-worker-", marketPool.getThreadNamePrefix());
        Object orderTarget = AopTestUtils.getUltimateTargetObject(orders);
        assertSame(orderPool, ReflectionTestUtils.getField(orderTarget, "orderExecutor"));
        assertSame(marketPool, ReflectionTestUtils.getField(market, "marketExecutor"));
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(worker));
    }

    @Test void schedulerUpdatesActiveAssetsAndMatchingHistoryButSkipsInactiveAssets() {
        long inactive = read(() -> asset("INACTIVE", AssetStatus.INACTIVE).getId());
        dispatch();
        amount("91", price(assetId)); assertEquals(List.of(bd("91.00")), histories(assetId));
        amount("90", price(inactive)); assertEquals(0, historyCount(inactive));
        read(() -> {
            PriceHistory history = em.createQuery("from PriceHistory", PriceHistory.class).getSingleResult();
            assertNotNull(history.getRecordedAt()); assertEquals(assetId, history.getAsset().getId());
            Object[] versions = (Object[]) em.createNativeQuery(
                    "select a.xmin::text, h.xmin::text from assets a join price_history h on h.asset_id = a.id where a.id = :id")
                    .setParameter("id", assetId).getSingleResult();
            assertEquals(versions[0], versions[1], "Asset and history must be written by the same PostgreSQL transaction");
            return null;
        });
    }

    @Test void distinctActiveAssetsUseConcurrentMarketWorkersAndCommitIndependentUpdates() {
        List<Long> ids = read(() -> List.of(assetId, asset("SECOND", AssetStatus.ACTIVE).getId(), asset("THIRD", AssetStatus.ACTIVE).getId()));
        try {
            tx.executeWithoutResult(status -> {
                ids.forEach(this::lock);
                market.updateMarketPrices();
                awaitCondition(() -> waitingWorkers() == 3, "Three market workers must concurrently wait for distinct asset locks");
                assertEquals(3, pool("marketExecutor").getActiveCount());
            });
        } finally { marketIdle(); }
        for (long id : ids) { amount("91", price(id)); assertEquals(List.of(bd("91.00")), histories(id)); }
    }

    @Test void conflictingUpdatesWaitForWriteLockAndUseLatestCommittedPrice() {
        CountDownLatch finished = new CountDownLatch(2);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        try {
            tx.executeWithoutResult(status -> {
                lock(assetId);
                for (int i = 0; i < 2; i++) pool("marketExecutor").execute(() -> {
                    try { worker.updateAssetPrice(assetId); } catch (Throwable failure) { failures.add(failure); } finally { finished.countDown(); }
                });
                awaitCondition(() -> waitingWorkers() == 2, "Both transactions must wait for the asset write lock");
                em.find(Asset.class, assetId).setCurrentPrice(bd("100"));
            });
        } finally {
            try { assertTrue(finished.await(15, TimeUnit.SECONDS)); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
        assertTrue(failures.isEmpty(), failures.toString());
        amount("102", price(assetId)); assertEquals(List.of(bd("101.00"), bd("102.00")), histories(assetId));
    }

    @Test void historyInsertFailureRollsBackAssetPriceAndAllowsRetry() {
        sql("ALTER TABLE price_history ADD CONSTRAINT market_reject_history CHECK (price < 0)");
        try {
            assertThrows(RuntimeException.class, () -> worker.updateAssetPrice(assetId));
            amount("90", price(assetId)); assertEquals(0, historyCount(assetId));
        } finally { sql("ALTER TABLE price_history DROP CONSTRAINT market_reject_history"); }
        worker.updateAssetPrice(assetId);
        amount("91", price(assetId)); assertEquals(List.of(bd("91.00")), histories(assetId));
    }

    @Test void assetWriteFailureRollsBackInsertedHistory() {
        sql("ALTER TABLE assets ADD CONSTRAINT market_reject_price CHECK (currentPrice <= 90)");
        try {
            assertThrows(RuntimeException.class, () -> worker.updateAssetPrice(assetId));
            amount("90", price(assetId)); assertEquals(0, historyCount(assetId));
        } finally { sql("ALTER TABLE assets DROP CONSTRAINT market_reject_price"); }
    }

    @Test void failedMarketTaskRollsBackWithoutBlockingHealthyAssetAndCanRetry() {
        long healthy = read(() -> asset("HEALTHY", AssetStatus.ACTIVE).getId());
        sql("ALTER TABLE price_history ADD CONSTRAINT market_reject_asset CHECK (asset_id <> " + assetId + ")");
        try {
            dispatch();
            amount("90", price(assetId)); assertEquals(0, historyCount(assetId));
            amount("91", price(healthy)); assertEquals(1, historyCount(healthy));
        } finally { sql("ALTER TABLE price_history DROP CONSTRAINT market_reject_asset"); }
        dispatch();
        amount("91", price(assetId)); assertEquals(1, historyCount(assetId));
        amount("92", price(healthy)); assertEquals(2, historyCount(healthy));
    }

    @Test void schedulerMustSkipAssetDeactivatedWhileWaitingForItsWriteLock() {
        try {
            tx.executeWithoutResult(status -> {
                lock(assetId); market.updateMarketPrices();
                awaitCondition(() -> waitingWorkers() == 1, "Market task must be waiting before deactivation");
                em.find(Asset.class, assetId).setAssetStatus(AssetStatus.INACTIVE);
            });
        } finally { marketIdle(); }
        assertAll(() -> amount("90", price(assetId)), () -> assertEquals(0, historyCount(assetId)));
    }

    long place(TransactionType type, String quantity, String limit) {
        CreateOrderRequest request = new CreateOrderRequest();
        ReflectionTestUtils.setField(request, "assetId", assetId); ReflectionTestUtils.setField(request, "orderType", type);
        ReflectionTestUtils.setField(request, "quantity", bd(quantity)); ReflectionTestUtils.setField(request, "limitPrice", bd(limit));
        orders.placeLimitOrder(email, request);
        return read(() -> em.createQuery("select max(o.id) from InvestmentOrder o", Long.class).getSingleResult());
    }

    @Test void automaticBuyAndSellAlongsideMarketUpdatesPreserveRecordedCashAndReservations() {
        tx.executeWithoutResult(status -> {
            Holding holding = new Holding(); holding.setPortfolio(em.find(Portfolio.class, portfolioId));
            holding.setAsset(em.find(Asset.class, assetId)); holding.setQuantity(bd("10")); holding.setAverageBuyPrice(bd("50")); em.persist(holding);
        });
        long buy = place(TransactionType.BUY, "2", "100"), sell = place(TransactionType.SELL, "3", "80");
        market.updateMarketPrices(); orders.executePendingOrders();
        marketIdle(); orderIdle();
        read(() -> {
            var transactions = em.createQuery("from Transaction", Transaction.class).getResultList();
            assertEquals(2, transactions.size());
            BigDecimal expectedCash = bd("1000");
            for (Transaction transaction : transactions) {
                BigDecimal price = transaction.getPricePerUnit();
                assertTrue(price.compareTo(bd("90")) == 0 || price.compareTo(bd("91")) == 0);
                amount(transaction.getQuantity().multiply(price).toPlainString(), transaction.getTotalAmount());
                expectedCash = transaction.getTransactionType() == TransactionType.BUY ? expectedCash.subtract(transaction.getTotalAmount()) : expectedCash.add(transaction.getTotalAmount());
            }
            Portfolio portfolio = em.find(Portfolio.class, portfolioId);
            amount(expectedCash.toPlainString(), portfolio.getCashBalance()); amount("0", portfolio.getReservedCash());
            Holding holding = em.createQuery("from Holding", Holding.class).getSingleResult();
            amount("9", holding.getQuantity()); amount("0", holding.getReservedQuantity());
            for (long id : new long[]{buy, sell}) {
                InvestmentOrder order = em.find(InvestmentOrder.class, id); assertEquals(OrderStatus.FILLED, order.getStatus()); assertNotNull(order.getExecutedAt());
            }
            return null;
        });
        amount("91", price(assetId)); assertEquals(1, historyCount(assetId));
    }
}
