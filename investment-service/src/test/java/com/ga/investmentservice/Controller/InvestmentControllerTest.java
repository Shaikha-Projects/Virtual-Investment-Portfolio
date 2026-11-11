package com.ga.investmentservice.Controller;

import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.DTO.Response.*;
import com.ga.investmentservice.Exception.*;
import com.ga.investmentservice.Filter.InvestmentRateLimitFilter;
import com.ga.investmentservice.Security.SecurityConfiguration;
import com.ga.investmentservice.Service.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InvestmentControllerTest {
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String ASSET = "{\"symbol\":\"ABC\",\"name\":\"Asset\",\"assetType\":\"STOCK\",\"currentPrice\":10}";
    private static final String TRADE = "{\"assetId\":1,\"quantity\":1}";
    private static final String ORDER = "{\"assetId\":1,\"quantity\":1,\"orderType\":\"BUY\",\"limitPrice\":10}";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @org.springframework.data.web.config.EnableSpringDataWebSupport
    @Import({SecurityConfiguration.class, InvestmentRateLimitFilter.class, RateLimitService.class,
            GlobalExceptionHandler.class, AssetController.class, WatchlistController.class,
            PortfolioController.class, PriceAlertController.class, InvestmentOrderController.class,
            AdminAssetController.class, AdminAuditLogController.class})
    static class Config {
        @Bean jakarta.persistence.EntityManagerFactory entityManagerFactory() {
            return mock(jakarta.persistence.EntityManagerFactory.class);
        }
        @Bean AssetService assets() { return mock(AssetService.class); }
        @Bean WatchlistService watchlist() { return mock(WatchlistService.class); }
        @Bean PortfolioSnapshotService snapshots() { return mock(PortfolioSnapshotService.class); }
        @Bean PriceAlertService alerts() { return mock(PriceAlertService.class); }
        @Bean InvestmentOrderService orders() { return mock(InvestmentOrderService.class); }
        @Bean MarketSimulationService market() { return mock(MarketSimulationService.class); }
        @Bean AuditLogService audit() { return mock(AuditLogService.class); }
        @Bean AuthServiceClient auth() { return mock(AuthServiceClient.class); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var properties = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            var values = new Properties();
            values.setProperty("jwt-secret", SECRET);
            properties.setProperties(values);
            return properties;
        }
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
        when(context.getBean(AuthServiceClient.class).getUserByEmail("owner@example.com"))
                .thenReturn(new UserIdentityResponse(42L, "owner@example.com", "USER", "ACTIVE"));
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    private String token() {
        return Jwts.builder().setSubject("owner@example.com")
                .setExpiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(SignatureAlgorithm.HS256, SECRET).compact();
    }

    private MockHttpServletRequestBuilder request(String method, String path, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .servletPath(path).contentType("application/json").content(body)
                .header("Authorization", "Bearer " + token());
    }

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("GET", "/assets", "", 200),
                Arguments.of("GET", "/assets/1/price-history", "", 200),
                Arguments.of("GET", "/watchlist", "", 200),
                Arguments.of("POST", "/watchlist/1", "", 200),
                Arguments.of("DELETE", "/watchlist/1", "", 200),
                Arguments.of("POST", "/portfolio/buy", TRADE, 200),
                Arguments.of("POST", "/portfolio/sell", TRADE, 200),
                Arguments.of("GET", "/portfolio/holdings", "", 200),
                Arguments.of("GET", "/portfolio/transactions", "", 200),
                Arguments.of("GET", "/portfolio/performance", "", 200),
                Arguments.of("GET", "/portfolio/history", "", 200),
                Arguments.of("GET", "/price-alerts", "", 200),
                Arguments.of("POST", "/price-alerts", "{\"assetId\":1,\"targetPrice\":11,\"condition\":\"ABOVE\"}", 201),
                Arguments.of("PATCH", "/price-alerts/1/cancel", "", 204),
                Arguments.of("GET", "/orders", "", 200),
                Arguments.of("POST", "/orders", ORDER, 200),
                Arguments.of("PATCH", "/orders/1/cancel", "", 200),
                Arguments.of("GET", "/admin/assets", "", 200),
                Arguments.of("POST", "/admin/assets", ASSET, 201),
                Arguments.of("PUT", "/admin/assets/1", ASSET, 200),
                Arguments.of("PATCH", "/admin/assets/1/status", "{\"assetStatus\":\"ACTIVE\"}", 200),
                Arguments.of("POST", "/admin/assets/execute-pending", "", 200),
                Arguments.of("GET", "/admin/audit-logs", "", 200));
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void originalEndpointMethodsAndStatusesArePreserved(String method, String path, String body, int expected) throws Exception {
        if (path.startsWith("/admin/")) {
            when(context.getBean(AuthServiceClient.class).getUserByEmail("owner@example.com"))
                    .thenReturn(new UserIdentityResponse(42L, "owner@example.com", "ADMIN", "ACTIVE"));
        }
        mvc.perform(request(method, path, body)).andExpect(status().is(expected));
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void everyEndpointRequiresAuthentication(String method, String path, String body, int expected) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                        .servletPath(path).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void investorCannotAccessAdministrationAndAdminCannotTrade() throws Exception {
        mvc.perform(request("POST", "/admin/assets/execute-pending", "")).andExpect(status().isForbidden());
        mvc.perform(request("GET", "/admin/audit-logs", "")).andExpect(status().isForbidden());
        when(context.getBean(AuthServiceClient.class).getUserByEmail("owner@example.com"))
                .thenReturn(new UserIdentityResponse(42L, "owner@example.com", "ADMIN", "ACTIVE"));
        mvc.perform(request("POST", "/portfolio/buy", TRADE)).andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(InvestmentOrderService.class));
    }

    @Test
    void authenticatedEmailAndOriginalOrderMessagesArePreserved() throws Exception {
        mvc.perform(request("POST", "/orders", ORDER).param("email", "victim@example.com"))
                .andExpect(status().isOk()).andExpect(content().string("Limit order placed successfully"));
        InvestmentOrderService orders = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                context.getBean(InvestmentOrderService.class));
        verify(orders).placeLimitOrder(eq("owner@example.com"), any());
        mvc.perform(request("PATCH", "/orders/1/cancel", ""))
                .andExpect(content().string("Order is successfully cancelled"));
    }

    @Test
    void invalidBodyUsesOriginalFieldErrorMapAndInvalidPaginationIsRejected() throws Exception {
        mvc.perform(request("POST", "/portfolio/buy", "{\"assetId\":1,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.quantity").value("Quantity must be greater than 0"));
        mvc.perform(request("GET", "/orders", "").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(request("GET", "/orders", "").param("size", "101"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(context.getBean(AssetService.class));
        verifyNoInteractions(context.getBean(InvestmentOrderService.class));
    }

    @Test
    void businessMissingAndDuplicateErrorsUseOriginalResponseShape() throws Exception {
        AssetService assets = context.getBean(AssetService.class);
        doThrow(new BusinessRuleException("Original business message")).when(assets).searchAssets(any(), any());
        mvc.perform(request("GET", "/assets", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Original business message")).andExpect(jsonPath("$.timestamp").exists());
        doThrow(new InformationNotFoundException("Asset does not exist")).when(assets).searchAssets(any(), any());
        mvc.perform(request("GET", "/assets", "")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.message").value("Asset does not exist"));
        doThrow(new InformationExistException("Asset symbol already exists")).when(assets).searchAssets(any(), any());
        mvc.perform(request("GET", "/assets", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409)).andExpect(jsonPath("$.message").value("Asset symbol already exists"));
    }

    @ParameterizedTest
    @CsvSource({"POST,/portfolio/buy", "POST,/portfolio/sell", "POST,/orders", "PATCH,/orders/1/cancel"})
    void sixthInvestmentRequestIsRateLimited(String method, String path) throws Exception {
        String body = path.equals("/orders") ? ORDER : TRADE;
        for (int i = 0; i < 5; i++) mvc.perform(request(method, path, body)).andExpect(status().isOk());
        mvc.perform(request(method, path, body)).andExpect(status().isTooManyRequests())
                .andExpect(content().contentType("application/json"))
                .andExpect(content().json("{\"message\":\"Too many requests. Please try again later.\"}"));
    }

    @Test
    void differentCancellationIdsShareOneBucket() throws Exception {
        for (int id = 1; id <= 5; id++) {
            mvc.perform(request("PATCH", "/orders/" + id + "/cancel", "")).andExpect(status().isOk());
        }
        mvc.perform(request("PATCH", "/orders/6/cancel", "")).andExpect(status().isTooManyRequests());
        InvestmentOrderService orders = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                context.getBean(InvestmentOrderService.class));
        verify(orders, times(5)).cancelOrder(eq("owner@example.com"), anyLong());
    }

    @Test
    void rateLimitBucketsRemainSeparateByInvestorAndEndpoint() {
        RateLimitService rateLimit = context.getBean(RateLimitService.class);
        for (int i = 0; i < 5; i++) assertTrue(rateLimit.allowInvestmentRequest("first", "/orders"));
        assertFalse(rateLimit.allowInvestmentRequest("first", "/orders"));
        assertTrue(rateLimit.allowInvestmentRequest("second", "/orders"));
        assertTrue(rateLimit.allowInvestmentRequest("first", "/portfolio/buy"));
    }
}
