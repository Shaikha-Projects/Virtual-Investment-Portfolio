package com.ga.investmentservice.Controller;

import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.DTO.Response.*;
import com.ga.investmentservice.Enums.*;
import com.ga.investmentservice.Exception.*;
import com.ga.investmentservice.Filter.InvestmentRateLimitFilter;
import com.ga.investmentservice.Security.SecurityConfiguration;
import com.ga.investmentservice.Service.RateLimitService;
import com.ga.investmentservice.Service.WalletService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WalletControllerTest {
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private final UUID reference = UUID.randomUUID();
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private WalletService walletService;
    private AuthServiceClient authServiceClient;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @org.springframework.data.web.config.EnableSpringDataWebSupport
    @Import({SecurityConfiguration.class, InvestmentRateLimitFilter.class, RateLimitService.class,
            GlobalExceptionHandler.class, WalletController.class})
    static class Config {
        @Bean WalletService walletService() { return mock(WalletService.class); }
        @Bean AuthServiceClient authServiceClient() { return mock(AuthServiceClient.class); }
        @Bean jakarta.persistence.EntityManagerFactory entityManagerFactory() {
            return mock(jakarta.persistence.EntityManagerFactory.class);
        }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var config = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            var values = new Properties();
            values.setProperty("jwt-secret", SECRET);
            config.setProperties(values);
            return config;
        }
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        walletService = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(context.getBean(WalletService.class));
        authServiceClient = context.getBean(AuthServiceClient.class);
        when(authServiceClient.getUserByEmail("owner@example.com"))
                .thenReturn(new UserIdentityResponse(42L, "owner@example.com", "USER", "ACTIVE"));
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    private String token() {
        return Jwts.builder().setSubject("owner@example.com").setExpiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(SignatureAlgorithm.HS256, SECRET).compact();
    }

    private MockHttpServletRequestBuilder create(String operation, String body) {
        return post("/portfolio/wallet/" + operation).contentType("application/json").content(body)
                .header("Authorization", "Bearer " + token()).header("Idempotency-Key", "wallet-1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void depositAndWithdrawalReturnCorrectCreationStatus(boolean created) throws Exception {
        when(walletService.createPendingDepositResult(any(), eq("wallet-1"))).thenReturn(new WalletService.WalletResult<>(
                new DepositResponse(reference, WalletOperationStatus.PENDING, "https://checkout.stripe.com/c/pay/example"), created));
        when(walletService.withdrawCashResult(any(), eq("wallet-1"))).thenReturn(new WalletService.WalletResult<>(
                new WithdrawalResponse(reference, WalletOperationStatus.COMPLETED, new BigDecimal("800.00")), created));
        mvc.perform(create("deposits", "{\"amount\":10}"))
                .andExpect(status().is(created ? 201 : 200)).andExpect(jsonPath("$.reference").value(reference.toString()))
                .andExpect(jsonPath("$.paymentUrl").value("https://checkout.stripe.com/c/pay/example"));
        mvc.perform(create("withdrawals", "{\"amount\":200}"))
                .andExpect(status().is(created ? 201 : 200)).andExpect(jsonPath("$.balanceAfter").value(800))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void verifiesAndRecoversDeposits() throws Exception {
        when(walletService.verifyPendingDeposit(reference)).thenReturn(new DepositResponse(reference, WalletOperationStatus.COMPLETED, null));
        when(walletService.recoverPendingDeposit(reference)).thenReturn(new DepositResponse(reference, WalletOperationStatus.PENDING, null));
        mvc.perform(post("/portfolio/wallet/deposits/" + reference + "/verify").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/portfolio/wallet/deposits/" + reference + "/recover").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void returnsSafeBalanceAndHistoryDtos() throws Exception {
        when(walletService.getWalletBalance()).thenReturn(new WalletBalanceResponse(new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("800")));
        when(walletService.getWalletHistory(0, 10)).thenReturn(new PageImpl<>(List.of(new WalletHistoryResponse(reference,
                WalletOperationType.WITHDRAWAL, new BigDecimal("200"), "USD", WalletOperationStatus.COMPLETED,
                new BigDecimal("800"), null, null)), org.springframework.data.domain.PageRequest.of(0, 10), 1));
        mvc.perform(get("/portfolio/wallet/balance").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cashBalance").value(1000))
                .andExpect(jsonPath("$.reservedCash").value(200)).andExpect(jsonPath("$.availableCash").value(800));
        mvc.perform(get("/portfolio/wallet/history").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].reference").value(reference.toString()))
                .andExpect(jsonPath("$.content[0].stripeCreationRequest").doesNotExist())
                .andExpect(jsonPath("$.content[0].portfolio").doesNotExist());
        verify(walletService).getWalletBalance();
        verify(walletService).getWalletHistory(0, 10);
        verifyNoMoreInteractions(walletService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposits", "withdrawals"})
    void requiresValidIdempotencyKeyAndAmount(String operation) throws Exception {
        mvc.perform(post("/portfolio/wallet/" + operation).contentType("application/json").content("{\"amount\":10}")
                .header("Authorization", "Bearer " + token())).andExpect(status().isBadRequest());
        for (String key : List.of(" ", "x".repeat(129))) {
            mvc.perform(post("/portfolio/wallet/" + operation).contentType("application/json").content("{\"amount\":10}")
                    .header("Authorization", "Bearer " + token()).header("Idempotency-Key", key)).andExpect(status().isBadRequest());
        }
        for (String body : List.of("{}", "{\"amount\":-1}", "{\"amount\":0.001}", "broken")) {
            mvc.perform(create(operation, body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(walletService);
    }

    @Test
    void rejectsInvalidPaginationAndReference() throws Exception {
        mvc.perform(get("/portfolio/wallet/history?page=-1").header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/portfolio/wallet/history?size=101").header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/portfolio/wallet/deposits/invalid/verify").header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(walletService);
    }

    @Test
    void preservesNotFoundConflictAndBusinessErrorResponses() throws Exception {
        when(walletService.verifyPendingDeposit(reference)).thenThrow(new InformationNotFoundException("Wallet operation does not exist"));
        when(walletService.recoverPendingDeposit(reference)).thenThrow(new InformationNotFoundException("Wallet operation does not exist"));
        for (String action : List.of("verify", "recover")) {
            mvc.perform(post("/portfolio/wallet/deposits/" + reference + "/" + action).header("Authorization", "Bearer " + token()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        }
        when(walletService.createPendingDepositResult(any(), anyString())).thenThrow(new InformationExistException("Idempotency conflict"));
        when(walletService.withdrawCashResult(any(), anyString())).thenThrow(new BusinessRuleException("Insufficient available cash"));
        mvc.perform(create("deposits", "{\"amount\":10}")).andExpect(status().isConflict());
        mvc.perform(create("withdrawals", "{\"amount\":10}")).andExpect(status().isBadRequest());
    }

    @Test
    void allRoutesRequireValidUserJwt() throws Exception {
        for (String path : List.of("balance", "history", "deposits", "withdrawals", "deposits/" + reference + "/verify", "deposits/" + reference + "/recover")) {
            boolean read = path.equals("balance") || path.equals("history");
            mvc.perform(read ? get("/portfolio/wallet/" + path) : post("/portfolio/wallet/" + path)).andExpect(status().isUnauthorized());
            mvc.perform((read ? get("/portfolio/wallet/" + path) : post("/portfolio/wallet/" + path))
                    .header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        }
        when(authServiceClient.getUserByEmail(anyString())).thenReturn(new UserIdentityResponse(42L, "owner@example.com", "ADMIN", "ACTIVE"));
        mvc.perform(get("/portfolio/wallet/balance").header("Authorization", "Bearer " + token())).andExpect(status().isForbidden());
        verifyNoInteractions(walletService);
    }

    @Test
    void corsAllowsIdempotencyHeader() throws Exception {
        mvc.perform(options("/portfolio/wallet/deposits").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("idempotency-key")));
        verifyNoInteractions(walletService);
    }

}
