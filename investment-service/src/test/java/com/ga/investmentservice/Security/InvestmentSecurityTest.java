package com.ga.investmentservice.Security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.sql.ResultSet;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InvestmentSecurityTest {
    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String EMAIL = "investor@example.com";
    private static final String USER_QUERY = "select id, email_address, role from users where email_address = ? and status = 'ACTIVE'";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private JdbcTemplate jdbc;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, CurrentUser.class, TestController.class})
    static class Config {
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
            var config = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
            var values = new Properties();
            values.setProperty("jwt-secret", SECRET);
            config.setProperties(values);
            return config;
        }
    }

    //Test-only endpoints exercise the security chain without migrating any controllers.
    @RestController
    static class TestController {
        private final CurrentUser currentUser;

        TestController(CurrentUser currentUser) { this.currentUser = currentUser; }

        @GetMapping("/portfolio/identity")
        String identity(java.security.Principal principal) {
            return currentUser.getUserId() + ":" + principal.getName();
        }

        @GetMapping("/admin/assets")
        String admin() { return "admin"; }

        @GetMapping("/assets")
        String assets() { return "assets"; }

        @GetMapping("/assets/admin-method")
        @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
        public String adminMethod() { return "admin"; }
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        clearInvocations(jdbc);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    private String token(String secret, Date expiration) {
        return Jwts.builder().setSubject(EMAIL).setExpiration(expiration)
                .signWith(SignatureAlgorithm.HS256, secret).compact();
    }

    private String validToken() {
        return token(SECRET, new Date(System.currentTimeMillis() + 60000));
    }

    private void activeUser(String role) throws Exception {
        //Exercise the actual row mapper as well as the exact parameterized ACTIVE-only query.
        when(jdbc.query(eq(USER_QUERY), org.mockito.ArgumentMatchers.<RowMapper<AuthenticatedUser>>any(), eq(EMAIL)))
                .thenAnswer(call -> {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getLong("id")).thenReturn(42L);
                    when(row.getString("email_address")).thenReturn(EMAIL);
                    when(row.getString("role")).thenReturn(role);
                    RowMapper<AuthenticatedUser> mapper = call.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
    }

    @Test
    void validTokenResolvesDatabaseIdentityAndIgnoresClientIdentity() throws Exception {
        activeUser("USER");
        String spoofedClaims = Jwts.builder().setSubject(EMAIL)
                .claim("userId", 999L).claim("role", "ADMIN")
                .setExpiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(SignatureAlgorithm.HS256, SECRET).compact();
        mvc.perform(get("/portfolio/identity").param("userId", "999").param("email", "victim@example.com")
                        .header("X-User-Id", "999").header("X-User-Email", "victim@example.com")
                        .header("Authorization", "Bearer " + spoofedClaims))
                .andExpect(status().isOk()).andExpect(content().string("42:" + EMAIL));
        mvc.perform(get("/admin/assets").header("Authorization", "Bearer " + spoofedClaims))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingMalformedExpiredWrongSignatureAndMissingExpiration() throws Exception {
        mvc.perform(get("/assets")).andExpect(status().isUnauthorized());
        for (String token : List.of("broken", token(SECRET, new Date(0)), token(SECRET, null),
                token(Base64.getEncoder().encodeToString("different-secret-of-at-least-32-bytes".getBytes()),
                        new Date(System.currentTimeMillis() + 60000)))) {
            mvc.perform(get("/assets").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/assets").param("token", validToken()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUsersExcludedByActiveStatusLookup() throws Exception {
        //Disabled, unverified and deleted users all produce no row from this ACTIVE-only lookup.
        when(jdbc.query(eq(USER_QUERY), org.mockito.ArgumentMatchers.<RowMapper<AuthenticatedUser>>any(), eq(EMAIL)))
                .thenReturn(List.of());
        mvc.perform(get("/assets").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isUnauthorized());
        verify(jdbc).query(eq(USER_QUERY), org.mockito.ArgumentMatchers.<RowMapper<AuthenticatedUser>>any(), eq(EMAIL));
    }

    @Test
    void checksCurrentRoleAndStatusOnEveryRequest() throws Exception {
        activeUser("USER");
        String token = validToken();
        mvc.perform(get("/portfolio/identity").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        when(jdbc.query(eq(USER_QUERY), org.mockito.ArgumentMatchers.<RowMapper<AuthenticatedUser>>any(), eq(EMAIL)))
                .thenReturn(List.of());
        mvc.perform(get("/portfolio/identity").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsCanAccessAdminRoutesAndAnnotatedMethods() throws Exception {
        activeUser("USER");
        mvc.perform(get("/admin/assets").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/assets/admin-method").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isForbidden());
        activeUser("ADMIN");
        mvc.perform(get("/admin/assets").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/assets/admin-method").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/portfolio/identity").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnknownDatabaseRoles() throws Exception {
        activeUser("UNKNOWN");
        mvc.perform(get("/assets").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void databaseFailureDoesNotAuthenticateUser() throws Exception {
        when(jdbc.query(eq(USER_QUERY), org.mockito.ArgumentMatchers.<RowMapper<AuthenticatedUser>>any(), eq(EMAIL)))
                .thenThrow(new DataAccessResourceFailureException("Database unavailable"));
        mvc.perform(get("/assets").header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void currentUserRejectsMissingOrUnverifiedPrincipal() {
        CurrentUser currentUser = context.getBean(CurrentUser.class);
        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUser::getUserId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client-supplied", null, List.of()));
        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUser::getUserId);
    }

    @Test
    void corsAllowsFrontendAuthorizationAndRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/portfolio/buy").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/portfolio/buy").header("Origin", "https://other.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }
}
