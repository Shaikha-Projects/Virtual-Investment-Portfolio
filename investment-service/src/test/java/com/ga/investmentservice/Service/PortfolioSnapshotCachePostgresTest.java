package com.ga.investmentservice.Service;

import com.ga.investmentservice.Config.CacheConfiguration;
import com.ga.investmentservice.Repository.*;
import com.ga.investmentservice.Security.AuthenticatedUser;
import com.ga.investmentservice.Security.CurrentUser;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.cache.CacheManager;
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

import javax.sql.DataSource;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

// Read-only integration check against a prepared isolated database; no application DB fallback.
@EnabledIfEnvironmentVariable(named = "INVESTMENT_HISTORY_POSTGRES_TEST", matches = "true")
class PortfolioSnapshotCachePostgresTest {
    @Configuration
    @EnableTransactionManagement
    @EnableMethodSecurity
    @EnableJpaRepositories(basePackageClasses = PortfolioRepository.class)
    @Import(CacheConfiguration.class)
    static class Config {
        @Bean DataSource dataSource() {
            String url = required("INVESTMENT_HISTORY_TEST_JDBC_URL");
            assertTrue(url.startsWith("jdbc:postgresql://"));
            String database = URI.create(url.substring(5)).getPath().substring(1);
            assertTrue(database.matches("investment_portfolio_test(?:_[A-Za-z0-9_]+)?"),
                    "Only an explicitly named isolated test database is allowed");
            return new DriverManagerDataSource(url, required("SPRING_DATASOURCE_USERNAME"),
                    required("SPRING_DATASOURCE_PASSWORD"));
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.ga.investmentservice.Model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.generate_statistics", "true",
                    "hibernate.physical_naming_strategy", org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl.class.getName(),
                    "hibernate.implicit_naming_strategy", org.springframework.boot.hibernate.SpringImplicitNamingStrategy.class.getName()));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean CurrentUser currentUser() { return new CurrentUser(); }
        @Bean PortfolioSnapshotService service(PortfolioRepository portfolios, HoldingRepository holdings,
                PortfolioSnapshotRepository snapshots, CurrentUser user, CacheManager manager) {
            return new PortfolioSnapshotService(portfolios, holdings, snapshots, user, manager);
        }
    }

    @Test
    void missLoadsActualPostgresHistoryAndHitSkipsOnlyTheHistoryQuery() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            var users = jdbc.queryForList("SELECT p.user_id, u.email_address FROM portfolios p JOIN users u ON u.id=p.user_id "
                    + "WHERE EXISTS (SELECT 1 FROM portfolio_snapshots s WHERE s.portfolio_id=p.id) ORDER BY p.id LIMIT 1");
            assumeFalse(users.isEmpty(), "Prepare a test user, portfolio, and at least one snapshot first");
            Long userId = ((Number) users.get(0).get("user_id")).longValue();
            String email = (String) users.get(0).get("email_address");
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    new AuthenticatedUser(userId, email, "USER"), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            var statistics = context.getBean(EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();
            statistics.clear();
            var service = context.getBean(PortfolioSnapshotService.class);
            var first = service.getPortfolioHistory(email);
            assertFalse(first.isEmpty());
            long missQueries = statistics.getQueryExecutionCount();
            assertEquals(2, missQueries, "Miss resolves portfolio and loads history");
            assertSame(first, service.getPortfolioHistory(email));
            assertEquals(missQueries + 1, statistics.getQueryExecutionCount(), "Hit still resolves portfolio ownership");
            var expected = jdbc.queryForList("SELECT s.total_portfolio_value FROM portfolio_snapshots s "
                    + "JOIN portfolios p ON p.id=s.portfolio_id WHERE p.user_id=? ORDER BY s.recorded_at", userId);
            assertEquals(expected.size(), first.size());
            for (int index = 0; index < first.size(); index++) {
                assertEquals(0, first.get(index).getTotalPortfolioValue().compareTo(
                        (java.math.BigDecimal) expected.get(index).get("total_portfolio_value")));
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " must be explicitly configured");
        return value;
    }
}
