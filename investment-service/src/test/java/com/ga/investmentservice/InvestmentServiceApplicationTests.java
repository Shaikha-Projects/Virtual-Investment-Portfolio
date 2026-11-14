package com.ga.investmentservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.ga.investmentservice.Repository.*;
import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.Client.StripeClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
        "jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "investment.market-simulation.enabled=false",
        "stripe.enabled=true",
        "myfatoorah.enabled=false"
})
@MockitoBean(types = {AssetRepository.class, HoldingRepository.class, TransactionRepository.class,
        PortfolioRepository.class, InvestmentOrderRepository.class, PortfolioSnapshotRepository.class,
        PriceAlertRepository.class, PriceHistoryRepository.class, WatchlistRepository.class,
        AuditLogRepository.class, OutboxEventRepository.class, AuthServiceClient.class, KafkaTemplate.class,
        WalletOperationRepository.class, PlatformTransactionManager.class, StripeClient.class,
        jakarta.persistence.EntityManagerFactory.class})
class InvestmentServiceApplicationTests {

	@Test
	void contextLoads() {
		org.junit.jupiter.api.Assertions.assertTrue(webApplicationContext.getBeansOfType(
				com.ga.investmentservice.Client.MyFatoorahClient.class).isEmpty());
		org.junit.jupiter.api.Assertions.assertNotNull(webApplicationContext.getBean(StripeClient.class));
	}

	@org.springframework.beans.factory.annotation.Autowired
	private org.springframework.web.context.WebApplicationContext webApplicationContext;

	@Test
	void swaggerIsPublicWithBearerSchemeAndBusinessEndpointsStayProtected() throws Exception {
		var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.addFilters(webApplicationContext.getBean(org.springframework.security.web.FilterChainProxy.class)).build();
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/swagger-ui.html"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is3xxRedirection());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/swagger-ui/index.html"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs/swagger-config"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/assets"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/assets"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
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
