package com.ga.notificationservice;

import com.ga.notificationservice.Controller.NotificationController;
import com.ga.notificationservice.Security.SecurityConfiguration;
import com.ga.notificationservice.Service.NotificationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotificationSecurityTests {
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private JdbcTemplate jdbc;
    private NotificationService notifications;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, NotificationController.class})
    static class Config {
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean NotificationService notifications() { return mock(NotificationService.class); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var config = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            var values = new Properties();
            values.setProperty("jwt-secret", SECRET);
            config.setProperties(values);
            return config;
        }
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        notifications = context.getBean(NotificationService.class);
        clearInvocations(jdbc, notifications);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { context.close(); }

    private String token(String secret, Date expiration) {
        return Jwts.builder().setSubject("owner@example.com").setExpiration(expiration)
                .signWith(SignatureAlgorithm.HS256, secret).compact();
    }
    private String validToken() { return token(SECRET, new Date(System.currentTimeMillis() + 60000)); }

    @Test void rejectsMissingMalformedExpiredAndWrongSignature() throws Exception {
        mvc.perform(get("/notifications/subscribe")).andExpect(status().isUnauthorized());
        for (String token : List.of("broken", token(SECRET, null), token(SECRET, new Date(0)),
                token(Base64.getEncoder().encodeToString("another-secret-of-at-least-32-bytes".getBytes()),
                        new Date(System.currentTimeMillis() + 60000)))) {
            mvc.perform(get("/notifications/subscribe").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(jdbc, notifications);
    }

    @Test void rejectsMissingDisabledOrUnverifiedUser() throws Exception {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("owner@example.com"))).thenReturn(List.of());
        mvc.perform(get("/notifications/subscribe").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isUnauthorized());
        verify(jdbc).queryForList("select email_address from users where email_address = ? and status = 'ACTIVE'",
                String.class, "owner@example.com");
        verifyNoInteractions(notifications);
    }

    @Test void subscribesOnlyAsAuthenticatedOwner() throws Exception {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("owner@example.com")))
                .thenReturn(List.of("owner@example.com"));
        when(notifications.subscribe("owner@example.com")).thenReturn(new SseEmitter());
        mvc.perform(get("/notifications/subscribe").param("email", "victim@example.com")
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk()).andExpect(request().asyncStarted());
        verify(notifications).subscribe("owner@example.com");
        verifyNoMoreInteractions(notifications);
    }

    @Test void corsAllowsFrontendBearerPreflightAndRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/notifications/subscribe").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/notifications/subscribe").header("Origin", "https://other.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(jdbc, notifications);
    }
}
