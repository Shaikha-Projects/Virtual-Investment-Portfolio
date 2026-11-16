package com.ga.investmentservice.Service;

import com.ga.investmentservice.Config.CacheConfiguration;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.PortfolioSnapshot;
import com.ga.investmentservice.Repository.*;
import com.ga.investmentservice.Security.AuthenticatedUser;
import com.ga.investmentservice.Security.CurrentUser;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.*;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Real Spring security/transaction proxies and Caffeine; repositories are mocked here.
class PortfolioSnapshotCacheTest {
    private AnnotationConfigApplicationContext context;
    private PortfolioSnapshotService service;
    private PortfolioRepository portfolios;
    private PortfolioSnapshotRepository snapshots;
    private Cache cache;
    private Portfolio first;
    private Portfolio second;

    @Configuration
    @EnableTransactionManagement
    @EnableMethodSecurity
    static class Config {
        @Bean PlatformTransactionManager transactionManager() {
            return new OutboxTransactionTest.TestTransactionManager();
        }
        @Bean PortfolioRepository portfolios() { return mock(PortfolioRepository.class); }
        @Bean HoldingRepository holdings() { return mock(HoldingRepository.class); }
        @Bean PortfolioSnapshotRepository snapshots() { return mock(PortfolioSnapshotRepository.class); }
        @Bean CurrentUser currentUser() { return spy(new CurrentUser()); }
        @Bean AtomicLong clock() { return new AtomicLong(); }
        @Bean CaffeineCacheManager cacheManager(AtomicLong clock) {
            CaffeineCacheManager manager = new CacheConfiguration().portfolioHistoryCacheManager();
            manager.setCaffeine(Caffeine.newBuilder().maximumSize(100)
                    .expireAfterWrite(Duration.ofSeconds(60)).ticker(clock::get));
            return manager;
        }
        @Bean PortfolioSnapshotService service(PortfolioRepository portfolios, HoldingRepository holdings,
                PortfolioSnapshotRepository snapshots, CurrentUser user, CaffeineCacheManager manager) {
            return new PortfolioSnapshotService(portfolios, holdings, snapshots, user, manager);
        }
    }

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(PortfolioSnapshotService.class);
        portfolios = context.getBean(PortfolioRepository.class);
        snapshots = context.getBean(PortfolioSnapshotRepository.class);
        cache = context.getBean(CaffeineCacheManager.class).getCache(CacheConfiguration.PORTFOLIO_HISTORY);
        first = portfolio(1L);
        second = portfolio(2L);
        when(portfolios.findByUserId(1L)).thenReturn(Optional.of(first));
        when(portfolios.findByUserId(2L)).thenReturn(Optional.of(second));
        when(portfolios.findAll()).thenReturn(List.of(first, second));
        when(snapshots.findByPortfolioOrderByRecordedAtAsc(first)).thenReturn(List.of(snapshot(first, "10.00")));
        when(snapshots.findByPortfolioOrderByRecordedAtAsc(second)).thenReturn(List.of(snapshot(second, "20.00")));
        login(1L, "USER");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void missLoadsDtosAndRepeatedRequestsStillValidateIdentityAndPortfolio() {
        var history = service.getPortfolioHistory(email(1L));
        assertEquals(new BigDecimal("10.00"), history.get(0).getTotalPortfolioValue());
        assertSame(history, service.getPortfolioHistory(email(1L)));
        verify(snapshots, times(1)).findByPortfolioOrderByRecordedAtAsc(first);
        verify(context.getBean(CurrentUser.class), times(2)).getUserId(email(1L));
        verify(portfolios, times(2)).findByUserId(1L);
        assertThrows(UnsupportedOperationException.class, history::clear);
    }

    @Test
    void usersHaveSeparateEntriesAndCannotSupplyAnotherUsersEmail() {
        service.getPortfolioHistory(email(1L));
        login(2L, "USER");
        assertThrows(AccessDeniedException.class, () -> service.getPortfolioHistory(email(1L)));
        assertEquals(new BigDecimal("20.00"), service.getPortfolioHistory(email(2L)).get(0).getTotalPortfolioValue());
        assertNotNull(cache.get(1L));
        assertNotNull(cache.get(2L));
    }

    @Test
    void populatedCacheCannotBypassRoleOrPrincipalOrPortfolioChecks() {
        service.getPortfolioHistory(email(1L));
        login(1L, "ADMIN");
        assertThrows(AccessDeniedException.class, () -> service.getPortfolioHistory(email(1L)));
        login(1L, "USER");
        when(portfolios.findByUserId(1L)).thenReturn(Optional.empty());
        assertThrows(InformationNotFoundException.class, () -> service.getPortfolioHistory(email(1L)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "unverified", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> service.getPortfolioHistory(email(1L)));
        SecurityContextHolder.clearContext();
        assertThrows(org.springframework.security.core.AuthenticationException.class,
                () -> service.getPortfolioHistory(email(1L)));
    }

    @Test
    void expirationIsAfterWriteAndHitsDoNotExtendIt() {
        service.getPortfolioHistory(email(1L));
        context.getBean(AtomicLong.class).set(Duration.ofSeconds(59).toNanos());
        service.getPortfolioHistory(email(1L));
        verify(snapshots, times(1)).findByPortfolioOrderByRecordedAtAsc(first);
        context.getBean(AtomicLong.class).set(Duration.ofSeconds(60).toNanos());
        service.getPortfolioHistory(email(1L));
        verify(snapshots, times(2)).findByPortfolioOrderByRecordedAtAsc(first);
    }

    @Test
    void productionConfigurationHasOnlyHistoryWithSixtySecondTtlAndOneHundredEntries() {
        var manager = new CacheConfiguration().portfolioHistoryCacheManager();
        assertEquals(Set.of(CacheConfiguration.PORTFOLIO_HISTORY), new HashSet<>(manager.getCacheNames()));
        var nativeCache = (com.github.benmanes.caffeine.cache.Cache<?, ?>)
                manager.getCache(CacheConfiguration.PORTFOLIO_HISTORY).getNativeCache();
        assertEquals(Duration.ofSeconds(60), nativeCache.policy().expireAfterWrite().orElseThrow().getExpiresAfter());
        assertEquals(100, nativeCache.policy().eviction().orElseThrow().getMaximum());
        assertNull(manager.getCache("prices"));
    }

    @Test
    void committedSnapshotEvictsOnlyItsOwnerAfterCommitAndNextReadReloads() {
        service.getPortfolioHistory(email(1L));
        login(2L, "USER");
        service.getPortfolioHistory(email(2L));
        write().executeWithoutResult(status -> {
            service.saveSnapshot(first);
            assertNotNull(cache.get(1L));
        });
        assertNull(cache.get(1L));
        assertNotNull(cache.get(2L));
        when(snapshots.findByPortfolioOrderByRecordedAtAsc(first)).thenReturn(List.of(snapshot(first, "30.00")));
        login(1L, "USER");
        assertEquals(new BigDecimal("30.00"), service.getPortfolioHistory(email(1L)).get(0).getTotalPortfolioValue());
        verify(snapshots, times(2)).findByPortfolioOrderByRecordedAtAsc(first);
    }

    @Test
    void rollbackPreservesPreviouslyCommittedCache() {
        var history = service.getPortfolioHistory(email(1L));
        write().executeWithoutResult(status -> {
            service.saveSnapshot(first);
            status.setRollbackOnly();
        });
        assertSame(history, service.getPortfolioHistory(email(1L)));
        verify(snapshots, times(1)).findByPortfolioOrderByRecordedAtAsc(first);
    }

    @Test
    void missInsideRolledBackWriteCannotPublishUncommittedHistory() {
        write().executeWithoutResult(status -> {
            service.saveSnapshot(first);
            service.getPortfolioHistory(email(1L));
            assertNull(cache.get(1L));
            status.setRollbackOnly();
        });
        assertNull(cache.get(1L));
        service.getPortfolioHistory(email(1L));
        verify(snapshots, times(2)).findByPortfolioOrderByRecordedAtAsc(first);
    }

    @Test
    void scheduledBatchInternalCallsEvictAllAffectedUsersAfterCommit() throws Exception {
        service.getPortfolioHistory(email(1L));
        login(2L, "USER");
        service.getPortfolioHistory(email(2L));
        write().executeWithoutResult(status -> {
            service.saveAllSnapshots();
            assertNotNull(cache.get(1L));
            assertNotNull(cache.get(2L));
        });
        assertNull(cache.get(1L));
        assertNull(cache.get(2L));
        verify(snapshots, times(2)).save(any(PortfolioSnapshot.class));
        assertEquals(300000, PortfolioSnapshotService.class.getMethod("saveAllSnapshots")
                .getAnnotation(Scheduled.class).fixedDelay());
    }

    private TransactionTemplate write() { return new TransactionTemplate(context.getBean(PlatformTransactionManager.class)); }
    private static String email(Long id) { return "history-" + id + "@example.com"; }
    private static void login(Long id, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(id, email(id), role), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    private static Portfolio portfolio(Long id) {
        Portfolio portfolio = new Portfolio();
        portfolio.setId(id + 10);
        portfolio.setUserId(id);
        portfolio.setCashBalance(new BigDecimal("100.00"));
        return portfolio;
    }
    private static PortfolioSnapshot snapshot(Portfolio portfolio, String value) {
        PortfolioSnapshot snapshot = new PortfolioSnapshot();
        snapshot.setPortfolio(portfolio);
        snapshot.setTotalPortfolioValue(new BigDecimal(value));
        snapshot.setRecordedAt(LocalDateTime.of(2026, 11, 16, 12, 0));
        return snapshot;
    }
}
