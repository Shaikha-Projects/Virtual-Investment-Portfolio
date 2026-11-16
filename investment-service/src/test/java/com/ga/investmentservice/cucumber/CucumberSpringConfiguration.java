package com.ga.investmentservice.cucumber;

import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.Controller.AssetController;
import com.ga.investmentservice.Filter.InvestmentRateLimitFilter;
import com.ga.investmentservice.Repository.*;
import com.ga.investmentservice.Security.CurrentUser;
import com.ga.investmentservice.Security.SecurityConfiguration;
import com.ga.investmentservice.Service.*;
import io.cucumber.spring.CucumberContextConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.mockito.Mockito.*;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = CucumberSpringConfiguration.AssetTestApplication.class,
        properties = {
                // Do not load application.properties or environment-specific connection settings.
                "spring.config.location=optional:classpath:/cucumber-no-application.properties",
                "server.address=127.0.0.1",
                "jwt-secret=" + CucumberSpringConfiguration.TEST_SECRET
        })
@MockitoBean(types = {AssetRepository.class, HoldingRepository.class, TransactionRepository.class,
        PortfolioRepository.class, WalletOperationRepository.class, AuthServiceClient.class,
        AuditLogService.class, TradeExecutionService.class, InvestmentEventService.class,
        MarketSimulationService.class})
public class CucumberSpringConfiguration {
    // Synthetic test material only; never read production credentials.
    static final String TEST_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    // An explicit configuration prevents Boot from adding the production application scan.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
            org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
            org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class,
            org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class
    })
    @Import({AssetController.class, AssetService.class, SecurityConfiguration.class, CurrentUser.class,
            InvestmentRateLimitFilter.class, RateLimitService.class})
    static class AssetTestApplication {
        // AssetService's unused persistence field must never resolve to a real database.
        @Bean
        EntityManagerFactory entityManagerFactory() {
            EntityManagerFactory factory = mock(EntityManagerFactory.class);
            when(factory.createEntityManager()).thenReturn(mock(EntityManager.class));
            when(factory.getProperties()).thenReturn(Map.of());
            return factory;
        }
    }
}
