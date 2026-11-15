package com.ga.investmentservice.Controller;

import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.DTO.Response.HelpResponse;
import com.ga.investmentservice.DTO.Response.UserIdentityResponse;
import com.ga.investmentservice.Exception.GlobalExceptionHandler;
import com.ga.investmentservice.Filter.InvestmentRateLimitFilter;
import com.ga.investmentservice.Security.SecurityConfiguration;
import com.ga.investmentservice.Service.HelpService;
import com.ga.investmentservice.Service.RateLimitService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HelpControllerTest {
    private static final String secret = Base64.getEncoder().encodeToString(new byte[32]);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private HelpService service;
    private AuthServiceClient auth;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, InvestmentRateLimitFilter.class, RateLimitService.class,
            GlobalExceptionHandler.class, HelpController.class})
    static class Config {
        @Bean AuthServiceClient authServiceClient() { return mock(AuthServiceClient.class); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var config = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            var values = new Properties();
            values.setProperty("jwt-secret", secret);
            config.setProperties(values);
            return config;
        }
    }

    @Configuration
    static class EnabledHelp {
        @Bean HelpService helpService() { return mock(HelpService.class); }
    }

    @BeforeEach
    void setup() {
        open(true);
    }

    private void open(boolean enabled) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        if (enabled) context.register(EnabledHelp.class);
        context.refresh();
        auth = context.getBean(AuthServiceClient.class);
        when(auth.getUserByEmail("owner@example.com"))
                .thenReturn(new UserIdentityResponse(42L, "owner@example.com", "USER", "ACTIVE"));
        if (enabled) {
            service = context.getBean(HelpService.class);
            when(service.answer(anyString())).thenReturn(new HelpResponse("Open Cash Wallet to deposit virtual funds.",
                    List.of(new HelpResponse.Source("user-guide-deposit", "Deposits"))));
        }
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    private String token(long expires) {
        return Jwts.builder().setSubject("owner@example.com").setExpiration(new Date(expires))
                .signWith(SignatureAlgorithm.HS256, secret).compact();
    }

    private MockHttpServletRequestBuilder ask(String body) {
        return post("/help/ask").contentType("application/json").content(body)
                .header("Authorization", "Bearer " + token(System.currentTimeMillis() + 60000));
    }

    @Test
    void authenticatedInvestorReceivesAnswerAndSourcesWithoutInternalFields() throws Exception {
        mvc.perform(ask("{\"question\":\"How do I deposit?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Open Cash Wallet to deposit virtual funds."))
                .andExpect(jsonPath("$.sources[0].sourceId").value("user-guide-deposit"))
                .andExpect(jsonPath("$.sources[0].title").value("Deposits"))
                .andExpect(jsonPath("$.available").doesNotExist());
        verify(service).answer("How do I deposit?");
    }

    @Test
    void missingInvalidAndExpiredTokensAreRejected() throws Exception {
        mvc.perform(post("/help/ask").contentType("application/json").content("{\"question\":\"Help\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/help/ask").contentType("application/json").content("{\"question\":\"Help\"}")
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/help/ask").contentType("application/json").content("{\"question\":\"Help\"}")
                        .header("Authorization", "Bearer " + token(System.currentTimeMillis() - 60000)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void administratorAndInactiveUserCannotAskQuestions() throws Exception {
        when(auth.getUserByEmail("owner@example.com"))
                .thenReturn(new UserIdentityResponse(42L, "owner@example.com", "ADMIN", "ACTIVE"));
        mvc.perform(ask("{\"question\":\"Help\"}")).andExpect(status().isForbidden());
        when(auth.getUserByEmail("owner@example.com"))
                .thenReturn(new UserIdentityResponse(42L, "owner@example.com", "USER", "INACTIVE"));
        mvc.perform(ask("{\"question\":\"Help\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"question\":null}", "{\"question\":\"\"}", "{\"question\":\"   \"}",
            "{\"question\":{}}", "{", "null"})
    void invalidRequestsAreRejected(String body) throws Exception {
        mvc.perform(ask(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void oversizedQuestionIsRejectedWithTheExistingValidationFormat() throws Exception {
        mvc.perform(ask("{\"question\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.question").value("Question must not exceed 2000 characters"));
        verifyNoInteractions(service);
    }

    @Test
    void sixthHelpRequestIsLimitedWithoutCallingTheModelAgain() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(ask("{\"question\":\"Help\"}")).andExpect(status().isOk());
        }
        mvc.perform(ask("{\"question\":\"Help\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.message").value("Too many Help requests. Please try again in one minute."));
        verify(service, times(5)).answer("Help");
    }

    @Test
    void helpAndTradingBudgetsAreIndependentAndUsersHaveSeparateHelpBuckets() {
        RateLimitService limits = context.getBean(RateLimitService.class);
        for (int attempt = 0; attempt < 5; attempt++) assertTrue(limits.allowHelpRequest("owner@example.com"));
        assertFalse(limits.allowHelpRequest("owner@example.com"));
        for (int attempt = 0; attempt < 5; attempt++) assertTrue(limits.allowInvestmentRequest("owner@example.com", "/portfolio/buy"));
        assertFalse(limits.allowInvestmentRequest("owner@example.com", "/portfolio/buy"));
        assertTrue(limits.allowHelpRequest("another@example.com"));
        assertTrue(limits.allowInvestmentRequest("owner@example.com", "/portfolio/sell"));
    }

    @Test
    void disabledHelpReturnsControlledUnavailableResponse() throws Exception {
        context.close();
        open(false);
        mvc.perform(ask("{\"question\":\"Help\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.answer").value("AI Help is currently disabled."))
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.available").doesNotExist());
        assertTrue(context.getBeansOfType(HelpService.class).isEmpty());
    }

    @Test
    void providerFailureReturnsSafe503InsteadOfASuccessStatus() throws Exception {
        when(service.answer(anyString())).thenReturn(new HelpResponse(
                "AI Help is temporarily unavailable. Please try again later.", List.of(), false));
        mvc.perform(ask("{\"question\":\"Help\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.answer").value("AI Help is temporarily unavailable. Please try again later."))
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.available").doesNotExist());
    }

    @Test
    void frontendCorsPreflightStillAllowsAuthenticatedJsonRequests() throws Exception {
        mvc.perform(options("/help/ask").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}
