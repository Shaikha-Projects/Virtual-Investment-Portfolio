package com.ga.investmentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.ga.investmentservice.Repository.*;
import com.ga.investmentservice.Client.AuthServiceClient;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest(properties = {
        "jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "investment.market-simulation.enabled=false"
})
@MockitoBean(types = {AssetRepository.class, HoldingRepository.class, TransactionRepository.class,
        PortfolioRepository.class, InvestmentOrderRepository.class, PortfolioSnapshotRepository.class,
        PriceAlertRepository.class, PriceHistoryRepository.class, WatchlistRepository.class,
        AuditLogRepository.class, OutboxEventRepository.class, AuthServiceClient.class, KafkaTemplate.class,
        jakarta.persistence.EntityManagerFactory.class})
class InvestmentServiceApplicationTests {

	@Test
	void contextLoads() {
	}

	@org.springframework.beans.factory.annotation.Autowired
	private com.ga.investmentservice.Service.AssetService assetService;

	@Test
	@org.springframework.security.test.context.support.WithMockUser(roles = "USER")
	void investorCannotCallAssetAdministrationService() {
		org.junit.jupiter.api.Assertions.assertThrows(org.springframework.security.access.AccessDeniedException.class,
				() -> assetService.getAllAssets());
	}

	@Test
	@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
	void adminCanListAssets() {
		org.junit.jupiter.api.Assertions.assertTrue(assetService.getAllAssets().isEmpty());
	}

}
