package com.ga.investmentservice.Service;

import com.ga.investmentservice.DTO.Response.PortfolioPerformanceResponse;
import com.ga.investmentservice.Enums.WalletOperationStatus;
import com.ga.investmentservice.Enums.WalletOperationType;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Repository.*;
import com.ga.investmentservice.Security.AuthenticatedUser;
import com.ga.investmentservice.Security.CurrentUser;
import jakarta.persistence.EntityManagerFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.DriverManager;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

// run only against a prepared, isolated test database
// enable this test and set its test database url
// use the existing database credentials
// cleanup removes only this test's new records
@EnabledIfEnvironmentVariable(named = "INVESTMENT_PERFORMANCE_POSTGRES_TEST", matches = "true")
class PortfolioPerformancePostgresTest {
    private static AnnotationConfigApplicationContext context;
    private static String testUrl;
    private static String username;
    private static String password;
    private final List<Fixture> fixtures = new ArrayList<>();

    @BeforeAll
    static void openIsolatedDatabase() throws Exception {
        testUrl = requiredEnvironment("INVESTMENT_PERFORMANCE_TEST_JDBC_URL");
        username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
        password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
        assertTrue(testUrl.startsWith("jdbc:postgresql://"), "An explicit PostgreSQL test URL is required");
        String database = URI.create(testUrl.substring(5)).getPath().substring(1);
        assertTrue(isTestDatabase(database), "Refusing any database outside investment_portfolio_test or its named variants");

        // check the test schema before adding test records
        try (var connection = DriverManager.getConnection(testUrl, username, password)) {
            assertTrue(isTestDatabase(connection.getCatalog()), "Refusing a non-test PostgreSQL database");
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            Set<String> tables = new HashSet<>();
            try (var rows = connection.getMetaData().getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (rows.next()) {
                    tables.add(rows.getString("TABLE_NAME"));
                }
            }
            connection.rollback();
            if (!tables.containsAll(Set.of("users", "portfolios", "wallet_operations", "assets", "holdings",
                    "transactions", "audit_logs", "investment_orders", "portfolio_snapshots", "price_alert",
                    "price_history", "watchlist", "investment_outbox"))) {
                return;
            }
        }
        context = new AnnotationConfigApplicationContext(Config.class);
    }

    @BeforeEach
    void requirePreparedIsolatedSchema() {
        assumeTrue(context != null,
                "Isolated PostgreSQL database lacks the Investment schema; prepare it explicitly before running this test");
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableMethodSecurity
    @EnableJpaRepositories(basePackageClasses = PortfolioRepository.class)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(testUrl, username, password);
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.ga.investmentservice.Model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.show_sql", "false",
                    "hibernate.physical_naming_strategy", org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl.class.getName(),
                    "hibernate.implicit_naming_strategy", org.springframework.boot.hibernate.SpringImplicitNamingStrategy.class.getName()));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean CurrentUser currentUser() { return new CurrentUser(); }
        @Bean Probe probe(DataSource dataSource) { return new Probe(new JdbcTemplate(dataSource)); }

        @Bean AssetService assetService(AssetRepository assets, CurrentUser currentUser, HoldingRepository holdings,
                                       TransactionRepository transactions, PortfolioRepository portfolios,
                                       WalletOperationRepository walletOperations, Probe probe) {
            // check the database connection inside the transaction
            ProxyFactory proxy = new ProxyFactory(walletOperations);
            proxy.setInterfaces(WalletOperationRepository.class);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                if (invocation.getMethod().getName().startsWith("sumCompleted")) {
                    probe.isolation.add(probe.jdbc.queryForObject("SHOW transaction_isolation", String.class));
                    probe.readOnly.add(probe.jdbc.queryForObject("SHOW transaction_read_only", String.class));
                    probe.backends.add(probe.jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                }
                return invocation.proceed();
            });
            return new AssetService(assets, currentUser, holdings, transactions, portfolios,
                    mock(AuditLogService.class), mock(TradeExecutionService.class), mock(InvestmentEventService.class),
                    (WalletOperationRepository) proxy.getProxy());
        }
    }

    static class Probe {
        final JdbcTemplate jdbc;
        final List<String> isolation = new ArrayList<>();
        final List<String> readOnly = new ArrayList<>();
        final List<Integer> backends = new ArrayList<>();
        Probe(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    }

    @Test
    void initialFundingAndCompletedAggregatesUseRealPostgresMappingsAndFilters() {
        Fixture fixture = fixture("100000.00", "100030.00");
        write().execute(status -> {
            wallet(fixture, WalletOperationType.DEPOSIT, WalletOperationStatus.COMPLETED, "50.00", "100050.00");
            wallet(fixture, WalletOperationType.WITHDRAWAL, WalletOperationStatus.COMPLETED, "20.00", "100030.00");
            for (WalletOperationStatus ignored : List.of(WalletOperationStatus.PENDING,
                    WalletOperationStatus.FAILED, WalletOperationStatus.CANCELLED)) {
                wallet(fixture, WalletOperationType.DEPOSIT, ignored, "999.00", null);
            }
            wallet(fixture, WalletOperationType.WITHDRAWAL, WalletOperationStatus.FAILED, "777.00", null);
            return null;
        });
        Fixture other = fixture("100000.00", "100500.00");
        write().execute(status -> {
            wallet(other, WalletOperationType.DEPOSIT, WalletOperationStatus.COMPLETED, "500.00", "100500.00");
            return null;
        });

        Portfolio mapped = context.getBean(PortfolioRepository.class).findById(fixture.portfolioId).orElseThrow();
        amount("100000.00", mapped.getInitialFunding());
        WalletOperationRepository wallet = context.getBean(WalletOperationRepository.class);
        amount("50.00", wallet.sumCompletedDepositsByPortfolioId(fixture.portfolioId));
        amount("20.00", wallet.sumCompletedWithdrawalsByPortfolioId(fixture.portfolioId));
        amount("500.00", wallet.sumCompletedDepositsByPortfolioId(other.portfolioId));
        amount("0", performance(fixture).getTotalGainLoss());
        amount("100030.00", jdbc().queryForObject("SELECT cash_balance FROM public.portfolios WHERE id = ?",
                BigDecimal.class, fixture.portfolioId));
    }

    @Test
    void emptyFundingAggregatesReturnZeroAndKeepLegacyPerformance() {
        Fixture fixture = fixture("100000.00", "100000.00");
        WalletOperationRepository wallet = context.getBean(WalletOperationRepository.class);
        amount("0", wallet.sumCompletedDepositsByPortfolioId(fixture.portfolioId));
        amount("0", wallet.sumCompletedWithdrawalsByPortfolioId(fixture.portfolioId));
        PortfolioPerformanceResponse result = performance(fixture);
        amount("0", result.getTotalGainLoss());
        amount("0", result.getTotalReturnPercentage());
    }

    @Test
    void zeroInitialFundingAndCompletedDepositDoNotCreateInvestmentProfit() {
        Fixture fixture = fixture("0.00", "50.00");
        write().execute(status -> {
            wallet(fixture, WalletOperationType.DEPOSIT, WalletOperationStatus.COMPLETED, "50.00", "50.00");
            return null;
        });
        PortfolioPerformanceResponse result = performance(fixture);
        amount("50.00", result.getTotalPortfolioValue());
        amount("0", result.getTotalGainLoss());
        amount("0", result.getTotalReturnPercentage());
    }

    @Test
    void existingHoldingAndRealizedGainCalculationsRemainUnchanged() {
        Fixture fixture = fixture("100000.00", "99125.40");
        write().execute(status -> {
            fixture.assetId = jdbc().queryForObject("""
                    INSERT INTO public.assets (symbol, name, asset_type, current_price, asset_status, created_at, updated_at)
                    VALUES (?, 'Performance test', 'STOCK', 120.00, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                    """, Long.class, "PERF" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            fixture.holdingId = jdbc().queryForObject("""
                    INSERT INTO public.holdings (portfolio_id, asset_id, quantity, average_buy_price, reserved_quantity, created_at, updated_at)
                    VALUES (?, ?, 10, 100.00, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                    """, Long.class, fixture.portfolioId, fixture.assetId);
            fixture.transactionId = jdbc().queryForObject("""
                    INSERT INTO public.transactions (portfolio_id, asset_id, transaction_type, quantity, price_per_unit,
                        total_amount, cost_basis, realized_gain_loss, transaction_status, created_at)
                    VALUES (?, ?, 'SELL', 1, 225.40, 225.40, 100.00, 125.40, 'COMPLETED', CURRENT_TIMESTAMP) RETURNING id
                    """, Long.class, fixture.portfolioId, fixture.assetId);
            return null;
        });
        PortfolioPerformanceResponse result = performance(fixture);
        amount("1200.00", result.getHoldingsValue());
        amount("1000.00", result.getTotalCostBasis());
        amount("200.00", result.getUnrealizedGainLoss());
        amount("125.40", result.getRealizedGainLoss());
        amount("325.40", result.getTotalGainLoss());
        assertEquals(new BigDecimal("0.3300"), result.getTotalReturnPercentage());
    }

    private PortfolioPerformanceResponse performance(Fixture fixture) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(fixture.userId, fixture.email, "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        Probe probe = context.getBean(Probe.class);
        probe.isolation.clear();
        probe.readOnly.clear();
        probe.backends.clear();
        PortfolioPerformanceResponse result = context.getBean(AssetService.class).getPortfolioPerformance(fixture.email);
        assertEquals(List.of("repeatable read", "repeatable read"), probe.isolation);
        assertEquals(List.of("on", "on"), probe.readOnly);
        assertEquals(2, probe.backends.size());
        assertEquals(probe.backends.get(0), probe.backends.get(1));
        return result;
    }

    private Fixture fixture(String initialFunding, String cash) {
        Fixture fixture = write().execute(status -> {
            String email = "performance-" + UUID.randomUUID() + "@example.invalid";
            Long userId = jdbc().queryForObject("""
                    INSERT INTO public.users (first_name, last_name, phone_number, email_address, password, role,
                        status, created_at, updated_at)
                    VALUES ('Performance', 'Test', '00000000000', ?, 'unusable-test-password', 'USER',
                        'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                    """, Long.class, email);
            Long portfolioId = jdbc().queryForObject("""
                    INSERT INTO public.portfolios (user_id, cash_balance, reserved_cash, initial_funding, created_at, updated_at)
                    VALUES (?, ?, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                    """, Long.class, userId, new BigDecimal(cash), new BigDecimal(initialFunding));
            return new Fixture(userId, portfolioId, email);
        });
        fixtures.add(fixture);
        return fixture;
    }

    private void wallet(Fixture fixture, WalletOperationType type, WalletOperationStatus status, String amount, String balance) {
        Long id = jdbc().queryForObject("""
                INSERT INTO public.wallet_operations (portfolio_id, reference, idempotency_key, operation_type,
                    amount, currency, payment_method, status, failure_code, balance_after, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 'USD', ?, ?, ?, ?, CURRENT_TIMESTAMP,
                    CASE WHEN ? = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END) RETURNING id
                """, Long.class, fixture.portfolioId, UUID.randomUUID(), UUID.randomUUID().toString(), type.name(),
                new BigDecimal(amount), type == WalletOperationType.DEPOSIT ? "DEMO_CARD" : null,
                status.name(), status == WalletOperationStatus.FAILED || status == WalletOperationStatus.CANCELLED
                        ? "TEST_" + status.name() : null,
                balance == null ? null : new BigDecimal(balance), status.name());
        fixture.walletIds.add(id);
    }

    @AfterEach
    void removeOnlyOwnedFixtures() {
        SecurityContextHolder.clearContext();
        if (context == null) {
            return;
        }
        write().execute(status -> {
            for (Fixture fixture : fixtures) {
                assertEquals(1, jdbc().queryForObject("SELECT count(*) FROM public.users WHERE id = ? AND email_address = ?",
                        Integer.class, fixture.userId, fixture.email));
                for (Long walletId : fixture.walletIds) {
                    jdbc().update("DELETE FROM public.wallet_operations WHERE id = ? AND portfolio_id = ?", walletId, fixture.portfolioId);
                }
                if (fixture.transactionId != null) {
                    jdbc().update("DELETE FROM public.transactions WHERE id = ? AND portfolio_id = ?", fixture.transactionId, fixture.portfolioId);
                }
                if (fixture.holdingId != null) {
                    jdbc().update("DELETE FROM public.holdings WHERE id = ? AND portfolio_id = ?", fixture.holdingId, fixture.portfolioId);
                }
                jdbc().update("DELETE FROM public.portfolios WHERE id = ? AND user_id = ?", fixture.portfolioId, fixture.userId);
                if (fixture.assetId != null) {
                    jdbc().update("DELETE FROM public.assets WHERE id = ?", fixture.assetId);
                }
                jdbc().update("DELETE FROM public.users WHERE id = ? AND email_address = ?", fixture.userId, fixture.email);
            }
            return null;
        });
    }

    private TransactionTemplate write() {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    private JdbcTemplate jdbc() { return context.getBean(Probe.class).jdbc; }
    private static boolean isTestDatabase(String name) {
        return name != null && name.matches("investment_portfolio_test(?:_[A-Za-z0-9_]+)?");
    }
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " is required; no application-database fallback is allowed");
        return value;
    }
    private static void amount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    static class Fixture {
        final Long userId;
        final Long portfolioId;
        final String email;
        final List<Long> walletIds = new ArrayList<>();
        Long assetId;
        Long holdingId;
        Long transactionId;
        Fixture(Long userId, Long portfolioId, String email) {
            this.userId = userId;
            this.portfolioId = portfolioId;
            this.email = email;
        }
    }
}
