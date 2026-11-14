package com.ga.investmentservice.Service;

import com.ga.investmentservice.Client.StripeClient;
import com.ga.investmentservice.Event.WalletCompletedEvent;
import com.ga.investmentservice.DTO.Request.DepositRequest;
import com.ga.investmentservice.DTO.Request.WithdrawalRequest;
import com.ga.investmentservice.DTO.Request.StripeCheckoutRequest;

import com.ga.investmentservice.DTO.Response.DepositResponse;
import com.ga.investmentservice.DTO.Response.StripeCheckoutSessionResponse;
import com.ga.investmentservice.DTO.Response.StripePaymentIntentResponse;

import com.ga.investmentservice.Enums.*;
import com.ga.investmentservice.Exception.*;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.WalletOperation;
import com.ga.investmentservice.Repository.PortfolioRepository;
import com.ga.investmentservice.Repository.WalletOperationRepository;
import com.ga.investmentservice.Security.AuthenticatedUser;
import com.ga.investmentservice.Security.CurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WalletServiceTest {
    private PortfolioRepository portfolioRepository;
    private WalletOperationRepository walletOperationRepository;
    private StripeClient stripeClient;
    private InvestmentEventService investmentEventService;
    private final List<WalletCompletedEvent> completedEvents = new CopyOnWriteArrayList<>();
    private WalletService walletService;
    private Portfolio portfolio;
    private TestTransactionManager transactionManager;
    private final Map<String, WalletOperation> operations = new ConcurrentHashMap<>();
    private final ReentrantLock portfolioLock = new ReentrantLock();
    private final AtomicLong ids = new AtomicLong();
    private final Instant now = Instant.parse("2026-11-12T12:00:00Z");
    private StripeClient requestBuilder;

    @BeforeEach
    void setUp() {
        authenticate();
        portfolioRepository = mock(PortfolioRepository.class);
        walletOperationRepository = mock(WalletOperationRepository.class);
        stripeClient = mock(StripeClient.class);
        investmentEventService = mock(InvestmentEventService.class);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(portfolioLock.isHeldByCurrentThread());
            WalletCompletedEvent event = call.getArgument(0);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { completedEvents.add(event); }
            });
            return null;
        }).when(investmentEventService).saveWalletEvent(any());
        transactionManager = new TestTransactionManager();
        walletService = new WalletService(portfolioRepository, walletOperationRepository, new CurrentUser(),
                stripeClient, investmentEventService, transactionManager, new BigDecimal("10000.00"), 23);
        ReflectionTestUtils.setField(walletService, "clock", Clock.fixed(now, ZoneOffset.UTC));
        requestBuilder = new StripeClient(org.springframework.web.client.RestClient.builder(), "sk_test_unit",
                "2026-10-28.endive", 1000, 2000, "https://example.com/return", "https://example.com/error");
        when(stripeClient.prepareCheckoutSession(any())).thenAnswer(call ->
                requestBuilder.prepareCheckoutSession(call.getArgument(0)));
        EntityManager entityManager = mock(EntityManager.class);
        doAnswer(call -> {
            assertTrue(portfolioLock.isHeldByCurrentThread());
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(entityManager).refresh(any());
        ReflectionTestUtils.setField(walletService, "entityManager", entityManager);

        portfolio = new Portfolio();
        portfolio.setId(5L);
        portfolio.setUserId(42L);
        portfolio.setCashBalance(new BigDecimal("1000.00"));
        portfolio.setReservedCash(new BigDecimal("200.00"));
        when(portfolioRepository.findByUserId(42L)).thenReturn(Optional.of(portfolio));
        when(portfolioRepository.findByIdForUpdate(5L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            portfolioLock.lock();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { portfolioLock.unlock(); }
            });
            return Optional.of(portfolio);
        });
        when(walletOperationRepository.findByPortfolio_IdAndIdempotencyKey(eq(5L), anyString()))
                .thenAnswer(call -> Optional.ofNullable(operations.get(call.getArgument(1))));
        when(walletOperationRepository.save(any(WalletOperation.class))).thenAnswer(call -> {
            assertTrue(portfolioLock.isHeldByCurrentThread());
            WalletOperation operation = call.getArgument(0);
            if (operation.getId() == null) {
                operation.setId(ids.incrementAndGet());
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { operations.put(operation.getIdempotencyKey(), operation); }
            });
            return operation;
        });
        when(walletOperationRepository.findByReferenceAndPortfolio_Id(any(UUID.class), eq(5L)))
                .thenAnswer(call -> operations.values().stream()
                        .filter(operation -> operation.getReference().equals(call.getArgument(0))).findFirst());
        when(walletOperationRepository.findWithLockById(anyLong())).thenAnswer(call -> {
            assertTrue(portfolioLock.isHeldByCurrentThread());
            return operations.values().stream().filter(operation -> operation.getId().equals(call.getArgument(0))).findFirst();
        });
        when(stripeClient.createCheckoutSession(anyMap(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(portfolioLock.isHeldByCurrentThread());
            Map<String, Object> request = call.getArgument(0);
            assertEquals(1, operations.size(), "The pending record must be committed before HTTP");
            return providerResponse(request);
        });
    }

    @AfterEach
    void cleanUp() {
        Thread.interrupted();
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsPendingDepositAndPersistsHostedInvoiceWithoutChangingCash() {
        DepositResponse response = walletService.createPendingDeposit(request("10.00"), "deposit-1");

        WalletOperation operation = operations.get("deposit-1");
        assertEquals(operation.getReference(), response.reference());
        assertEquals(WalletOperationStatus.PENDING, response.status());
        assertEquals("https://checkout.stripe.com/c/pay/example", response.paymentUrl());
        assertEquals("cs_test_example", operation.getStripeSessionId());
        assertEquals(WalletOperationType.DEPOSIT, operation.getOperationType());
        assertEquals(SimulatedPaymentMethod.STRIPE_SANDBOX, operation.getPaymentMethod());
        assertEquals("USD", operation.getCurrency());
        assertNull(operation.getBalanceAfter());
        assertNull(operation.getCompletedAt());
        assertNull(operation.getFailureCode());
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
        verify(portfolioRepository, never()).save(any());
        verify(stripeClient).createCheckoutSession(eq(operation.getStripeCreationRequest()),
                eq("wallet-deposit:" + operation.getReference()));
        assertEquals(now.toString(), operation.getStripeCreationRequest().get("createdAt"));
        verifyNoInteractions(investmentEventService);
        assertEquals("2026-10-28.endive", operation.getStripeCreationRequest().get("apiVersion"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "10.000", "10000.01", "10000000000000"})
    void rejectsInvalidOrOverLimitAmounts(String amount) {
        assertThrows(BusinessRuleException.class,
                () -> walletService.createPendingDeposit(request(amount), "deposit-1"));
        assertTrue(operations.isEmpty());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void rejectsMissingAmount() {
        assertThrows(BusinessRuleException.class,
                () -> walletService.createPendingDeposit(new DepositRequest(), "deposit-1"));
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void requiresIdempotencyKey(String key) {
        assertThrows(BusinessRuleException.class,
                () -> walletService.createPendingDeposit(request("10.00"), key));
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void rejectsOversizedIdempotencyKey() {
        assertThrows(BusinessRuleException.class,
                () -> walletService.createPendingDeposit(request("10.00"), "x".repeat(129)));
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void duplicateKeyReusesExistingCheckout() {
        DepositResponse first = walletService.createPendingDeposit(request("10"), "deposit-1");
        assertEquals(first, walletService.createPendingDeposit(request("10.00"), "deposit-1"));
        assertEquals(1, operations.size());
        verify(stripeClient, times(1)).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void duplicateKeyWithDifferentAmountIsConflict() {
        walletService.createPendingDeposit(request("10.00"), "deposit-1");
        assertThrows(InformationExistException.class,
                () -> walletService.createPendingDeposit(request("20.00"), "deposit-1"));
        verify(stripeClient, times(1)).createCheckoutSession(anyMap(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"withdrawal", "currency", "method"})
    void duplicateKeyWithDifferentOperationDetailsIsConflict(String changed) {
        walletService.createPendingDeposit(request("10.00"), "deposit-1");
        WalletOperation operation = operations.get("deposit-1");
        switch (changed) {
            case "withdrawal" -> operation.setOperationType(WalletOperationType.WITHDRAWAL);
            case "currency" -> operation.setCurrency("KWD");
            default -> operation.setPaymentMethod(SimulatedPaymentMethod.DEMO_CARD);
        }
        assertThrows(InformationExistException.class,
                () -> walletService.createPendingDeposit(request("10.00"), "deposit-1"));
        verify(stripeClient, times(1)).createCheckoutSession(anyMap(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "malformed response", "network interrupted"})
    void ambiguousCreationRemainsPendingAndIsNeverAutomaticallyRetried(String reason) {
        doThrow(new IllegalStateException(reason)).when(stripeClient).createCheckoutSession(anyMap(), anyString());
        DepositResponse first = walletService.createPendingDeposit(request("10.00"), "deposit-1");
        DepositResponse retry = walletService.createPendingDeposit(request("10.00"), "deposit-1");
        assertEquals(first, retry);
        assertEquals(WalletOperationStatus.PENDING, retry.status());
        assertNull(retry.paymentUrl());
        assertNull(operations.get("deposit-1").getStripeSessionId());
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
        verify(stripeClient, times(1)).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void interruptedCreatorDoesNotCallProviderOrRetryOnNextRequest() {
        Thread.currentThread().interrupt();
        DepositResponse first = walletService.createPendingDeposit(request("10.00"), "deposit-1");
        Thread.interrupted();
        assertEquals(first, walletService.createPendingDeposit(request("10.00"), "deposit-1"));
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void concurrentDuplicateWhileProviderIsRunningCreatesOnlyOneInvoice() throws Exception {
        CountDownLatch enteredProvider = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(portfolioLock.isLocked());
            enteredProvider.countDown();
            assertTrue(releaseProvider.await(5, TimeUnit.SECONDS));
            return providerResponse(call.getArgument(0));
        }).when(stripeClient).createCheckoutSession(anyMap(), anyString());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DepositResponse> creator = executor.submit(() -> {
                authenticate();
                try { return walletService.createPendingDeposit(request("10.00"), "deposit-1"); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(enteredProvider.await(5, TimeUnit.SECONDS));
            Future<DepositResponse> duplicate = executor.submit(() -> {
                authenticate();
                try { return walletService.createPendingDeposit(request("10.00"), "deposit-1"); }
                finally { SecurityContextHolder.clearContext(); }
            });
            DepositResponse pending = duplicate.get(5, TimeUnit.SECONDS);
            assertNull(pending.paymentUrl());
            releaseProvider.countDown();
            DepositResponse completedCreation = creator.get(5, TimeUnit.SECONDS);
            assertEquals(pending.reference(), completedCreation.reference());
            assertEquals(1, operations.size());
            verify(stripeClient, times(1)).createCheckoutSession(anyMap(), anyString());
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void simultaneousRequestsShareOneCommittedCreationClaim() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<DepositResponse> create = () -> {
            authenticate();
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return walletService.createPendingDeposit(request("10.00"), "deposit-1");
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        try {
            Future<DepositResponse> first = executor.submit(create);
            Future<DepositResponse> second = executor.submit(create);
            start.countDown();
            assertEquals(first.get(5, TimeUnit.SECONDS).reference(), second.get(5, TimeUnit.SECONDS).reference());
            assertEquals(1, operations.size());
            assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
            assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
            verify(stripeClient, times(1)).createCheckoutSession(anyMap(), anyString());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void persistenceFailureAfterProviderSuccessDoesNotCreateAnotherInvoiceOnRetry() {
        doThrow(new IllegalStateException("Database unavailable"))
                .when(walletOperationRepository).findWithLockById(anyLong());
        assertThrows(IllegalStateException.class,
                () -> walletService.createPendingDeposit(request("10.00"), "deposit-1"));
        DepositResponse retry = walletService.createPendingDeposit(request("10.00"), "deposit-1");
        assertEquals(WalletOperationStatus.PENDING, retry.status());
        assertNull(retry.paymentUrl());
        verify(stripeClient, times(1)).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void pendingRecordFailurePreventsProviderCall() {
        doThrow(new IllegalStateException("Database unavailable")).when(walletOperationRepository).save(any());
        assertThrows(IllegalStateException.class,
                () -> walletService.createPendingDeposit(request("10.00"), "deposit-1"));
        assertTrue(operations.isEmpty());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void initialCommitFailurePreventsProviderCall() {
        transactionManager.failCommit = true;
        assertThrows(IllegalStateException.class,
                () -> walletService.createPendingDeposit(request("10.00"), "deposit-1"));
        assertTrue(operations.isEmpty());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void missingCheckoutConfigurationDoesNotSavePendingDeposit() {
        doThrow(new IllegalArgumentException("Invalid return URLs")).when(stripeClient).prepareCheckoutSession(any());
        assertThrows(IllegalArgumentException.class,
                () -> walletService.createPendingDeposit(request("10.00"), "deposit-1"));
        assertTrue(operations.isEmpty());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void rejectsOuterTransactionBeforeProviderOrRepositoryAccess() {
        new TransactionTemplate(transactionManager).execute(transaction -> {
            assertThrows(IllegalStateException.class,
                    () -> walletService.createPendingDeposit(request("10.00"), "deposit-1"));
            return null;
        });
        verifyNoInteractions(stripeClient, portfolioRepository, walletOperationRepository);
    }

    @Test
    void requiresVerifiedCurrentUser() {
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> walletService.createPendingDeposit(request("10.00"), "deposit-1"));
        verifyNoInteractions(stripeClient, portfolioRepository);
    }

    @Test
    void missingPortfolioDoesNotCreateOneOrContactProvider() {
        when(portfolioRepository.findByUserId(42L)).thenReturn(Optional.empty());
        assertThrows(InformationNotFoundException.class,
                () -> walletService.createPendingDeposit(request("10.00"), "deposit-1"));
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
        verify(portfolioRepository, never()).createInitialPortfolio(anyLong());
    }

    @Test
    void recoversInvoiceDetailsWithoutCreditingOrCreatingAnotherPayment() {
        WalletOperation operation = pendingDeposit();
        stubRecovery(operation);

        DepositResponse response = walletService.recoverPendingDeposit(operation.getReference());

        assertEquals(operation.getReference(), response.reference());
        assertEquals(WalletOperationStatus.PENDING, response.status());
        assertNull(response.paymentUrl());
        assertEquals("cs_test_example", operation.getStripeSessionId());
        assertEquals("complete", operation.getProviderInvoiceStatus());
        assertEquals("paid", operation.getProviderTransactionStatus());
        assertNull(operation.getBalanceAfter());
        assertNull(operation.getCompletedAt());
        assertNull(operation.getProviderPaymentId());
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
        verify(portfolioRepository, never()).save(any());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
        verify(stripeClient).getCheckoutSession("cs_test_example");
    }

    @Test
    void repeatedRecoveryPreservesOriginalCheckoutUrl() {
        WalletOperation operation = pendingDeposit();
        operation.setPaymentUrl("https://checkout.stripe.com/original-checkout");
        stubRecovery(operation);
        DepositResponse first = walletService.recoverPendingDeposit(operation.getReference());
        assertEquals(first, walletService.recoverPendingDeposit(operation.getReference()));
        assertEquals("https://checkout.stripe.com/original-checkout", first.paymentUrl());
        assertEquals(WalletOperationStatus.PENDING, operation.getStatus());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reference", "amount", "invoice"})
    void rejectsRecoveryIdentityOrAmountMismatchWithoutSaving(String changed) {
        WalletOperation operation = pendingDeposit();
        if (changed.equals("invoice")) {
            operation.setStripeSessionId("cs_test_other");
        }
        StripeCheckoutSessionResponse response = recoveryResponse(operation.getReference().toString(),
                "10.000 USD", "Paid");
        if (changed.equals("reference")) {
            response = recoveryResponse(UUID.randomUUID().toString(), "10.000 USD", "Paid");
        } else if (changed.equals("amount")) {
            response = recoveryResponse(operation.getReference().toString(), "20.000 USD", "Paid");
        }
        when(stripeClient.getCheckoutSession(anyString())).thenReturn(response);

        Class<? extends RuntimeException> expected = changed.equals("invoice")
                ? InformationExistException.class : BusinessRuleException.class;
        assertThrows(expected,
                () -> walletService.recoverPendingDeposit(operation.getReference()));
        verify(walletOperationRepository, never()).save(any());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
        assertEquals(changed.equals("invoice") ? "cs_test_other" : "cs_test_example", operation.getStripeSessionId());
        assertEquals(WalletOperationStatus.PENDING, operation.getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unknown invoice", "Lookup timed out", "Provider unavailable"})
    void unsuccessfulRecoveryLeavesOperationUnresolved(String failure) {
        WalletOperation operation = pendingDeposit();
        operation.setPaymentUrl("https://checkout.stripe.com/original-checkout");
        when(stripeClient.getCheckoutSession(anyString())).thenThrow(new IllegalStateException(failure));

        DepositResponse response = walletService.recoverPendingDeposit(operation.getReference());

        assertEquals(WalletOperationStatus.PENDING, response.status());
        assertEquals(operation.getPaymentUrl(), response.paymentUrl());
        assertEquals("cs_test_example", operation.getStripeSessionId());
        assertNull(operation.getBalanceAfter());
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
        verify(walletOperationRepository, never()).save(any());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "FAILED", "CANCELLED", "WITHDRAWAL", "DEMO_CARD", "KWD"})
    void recoveryRequiresPendingMyFatoorahUsdDeposit(String changed) {
        WalletOperation operation = pendingDeposit();
        switch (changed) {
            case "WITHDRAWAL" -> operation.setOperationType(WalletOperationType.WITHDRAWAL);
            case "DEMO_CARD" -> operation.setPaymentMethod(SimulatedPaymentMethod.DEMO_CARD);
            case "KWD" -> operation.setCurrency("KWD");
            default -> operation.setStatus(WalletOperationStatus.valueOf(changed));
        }
        assertThrows(BusinessRuleException.class,
                () -> walletService.recoverPendingDeposit(operation.getReference()));
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void recoveryCannotAccessAnotherPortfolio() {
        WalletOperation operation = pendingDeposit();
        Portfolio other = new Portfolio();
        other.setId(6L);
        other.setUserId(99L);
        operation.setPortfolio(other);

        assertThrows(InformationNotFoundException.class,
                () -> walletService.recoverPendingDeposit(operation.getReference()));
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
        verify(walletOperationRepository, never()).save(any());
    }

    @Test
    void unknownWalletReferenceIsNotSentToProvider() {
        assertThrows(InformationNotFoundException.class,
                () -> walletService.recoverPendingDeposit(UUID.randomUUID()));
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void recoveryRechecksInvoiceIdAfterProviderCall() {
        WalletOperation operation = pendingDeposit();
        when(stripeClient.getCheckoutSession(anyString())).thenAnswer(call -> {
            operation.setStripeSessionId("cs_test_other");
            return recoveryResponse(operation.getReference().toString(), "10.000 USD", "Paid");
        });
        assertThrows(InformationExistException.class,
                () -> walletService.recoverPendingDeposit(operation.getReference()));
        assertEquals("cs_test_other", operation.getStripeSessionId());
        verify(walletOperationRepository, never()).save(any());
    }

    @Test
    void recoveryCannotRevertCompletedOperationAfterProviderCall() {
        WalletOperation operation = pendingDeposit();
        when(stripeClient.getCheckoutSession(anyString())).thenAnswer(call -> {
            operation.setStatus(WalletOperationStatus.COMPLETED);
            return recoveryResponse(operation.getReference().toString(), "10.000 USD", "Paid");
        });
        assertThrows(BusinessRuleException.class,
                () -> walletService.recoverPendingDeposit(operation.getReference()));
        assertEquals(WalletOperationStatus.COMPLETED, operation.getStatus());
        verify(walletOperationRepository, never()).save(any());
    }

    @Test
    void concurrentRecoveryKeepsOneInvoiceAndUnchangedBalances() throws Exception {
        WalletOperation operation = pendingDeposit();
        CountDownLatch bothInquiries = new CountDownLatch(2);
        when(stripeClient.getCheckoutSession(anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(portfolioLock.isHeldByCurrentThread());
            bothInquiries.countDown();
            assertTrue(bothInquiries.await(5, TimeUnit.SECONDS));
            return recoveryResponse(operation.getReference().toString(), "10.000 USD", "Paid");
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<DepositResponse> recover = () -> {
            authenticate();
            try { return walletService.recoverPendingDeposit(operation.getReference()); }
            finally { SecurityContextHolder.clearContext(); }
        };
        try {
            Future<DepositResponse> first = executor.submit(recover);
            Future<DepositResponse> second = executor.submit(recover);
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals("cs_test_example", operation.getStripeSessionId());
            assertEquals(WalletOperationStatus.PENDING, operation.getStatus());
            assertEquals(1, operations.size());
            assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
            assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
            verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recoversPendingInvoiceWithFailedAttemptWithoutCompletingDeposit() {
        WalletOperation operation = pendingDeposit();
        when(stripeClient.getCheckoutSession(anyString())).thenReturn(
                recoveryResponse(operation.getReference().toString(), "10.000 USD", "Pending"));

        walletService.recoverPendingDeposit(operation.getReference());

        assertEquals("open", operation.getProviderInvoiceStatus());
        assertEquals("unpaid", operation.getProviderTransactionStatus());
        assertEquals(WalletOperationStatus.PENDING, operation.getStatus());
        assertNull(operation.getCompletedAt());
        assertNull(operation.getBalanceAfter());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void staleUnpaidInquiryDoesNotOverwriteObservedPaidStatus() {
        WalletOperation operation = pendingDeposit();
        operation.setStripeSessionId("cs_test_example");
        operation.setProviderInvoiceStatus("complete");
        operation.setProviderTransactionStatus("paid");
        when(stripeClient.getCheckoutSession(anyString())).thenReturn(
                recoveryResponse(operation.getReference().toString(), "10.000 USD", "Pending"));
        walletService.recoverPendingDeposit(operation.getReference());
        assertEquals("complete", operation.getProviderInvoiceStatus());
        assertEquals("paid", operation.getProviderTransactionStatus());
    }

    @Test
    void recoveryRejectsOuterTransactionAndMissingIdentity() {
        new TransactionTemplate(transactionManager).execute(transaction -> {
            assertThrows(IllegalStateException.class, () -> walletService.recoverPendingDeposit(UUID.randomUUID()));
            return null;
        });
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> walletService.recoverPendingDeposit(UUID.randomUUID()));
        verifyNoInteractions(stripeClient, portfolioRepository);
    }

    @Test
    void missingSessionRecoveryReplaysSavedRequestAndOriginalKeyWithinWindow() {
        WalletOperation operation = pendingDeposit();
        operation.setStripeSessionId(null);
        Map<String, Object> original = new LinkedHashMap<>(operation.getStripeCreationRequest());

        DepositResponse response = walletService.recoverPendingDeposit(operation.getReference());

        assertEquals("cs_test_example", operation.getStripeSessionId());
        assertEquals(WalletOperationStatus.PENDING, response.status());
        assertEquals("https://checkout.stripe.com/c/pay/example", response.paymentUrl());
        assertEquals(original, operation.getStripeCreationRequest());
        verify(stripeClient).createCheckoutSession(eq(original), eq("wallet-deposit:" + operation.getReference()));
        verify(stripeClient, never()).getCheckoutSession(anyString());
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
    }

    @ParameterizedTest
    @ValueSource(ints = {23, 24, 48})
    void unresolvedCheckoutOutsideSafeWindowRequiresManualReconciliation(int hours) {
        WalletOperation operation = pendingDeposit();
        operation.setStripeSessionId(null);
        operation.getStripeCreationRequest().put("createdAt", now.minusSeconds(hours * 3600L).toString());

        DepositResponse response = walletService.recoverPendingDeposit(operation.getReference());

        assertEquals(WalletOperationStatus.PENDING, response.status());
        assertNull(response.paymentUrl());
        assertNull(operation.getStripeSessionId());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
        verify(stripeClient, never()).getCheckoutSession(anyString());
        verify(walletOperationRepository, never()).save(any());
    }

    @Test
    void savedRequestCanBeReplayedJustBeforeTheSafeWindowEnds() {
        WalletOperation operation = pendingDeposit();
        operation.setStripeSessionId(null);
        operation.getStripeCreationRequest().put("createdAt", now.minusSeconds(23 * 3600L - 1).toString());
        walletService.recoverPendingDeposit(operation.getReference());
        verify(stripeClient).createCheckoutSession(eq(operation.getStripeCreationRequest()),
                eq("wallet-deposit:" + operation.getReference()));
        assertEquals("cs_test_example", operation.getStripeSessionId());
    }

    @Test
    void creationRequestSurvivesJsonSerializationBeforeRecovery() {
        WalletOperation operation = pendingDeposit();
        operation.setStripeSessionId(null);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        Map<String, Object> original = new LinkedHashMap<>(operation.getStripeCreationRequest());
        operation.setStripeCreationRequest(mapper.readValue(mapper.writeValueAsString(original),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        walletService.recoverPendingDeposit(operation.getReference());
        assertEquals(original, operation.getStripeCreationRequest());
        verify(stripeClient).createCheckoutSession(eq(original), eq("wallet-deposit:" + operation.getReference()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "future"})
    void unknownRequestAgeNeverCreatesAnotherCheckout(String age) {
        WalletOperation operation = pendingDeposit();
        operation.setStripeSessionId(null);
        switch (age) {
            case "missing" -> operation.setStripeCreationRequest(null);
            case "invalid" -> operation.getStripeCreationRequest().put("createdAt", "unknown");
            default -> operation.getStripeCreationRequest().put("createdAt", now.plusSeconds(1).toString());
        }
        walletService.recoverPendingDeposit(operation.getReference());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
        verify(stripeClient, never()).getCheckoutSession(anyString());
        assertNull(operation.getStripeSessionId());
    }

    @Test
    void knownSessionIsRetrievedAfterRetryWindowWithoutAnyCreationCall() {
        WalletOperation operation = pendingDeposit();
        operation.getStripeCreationRequest().put("createdAt", now.minusSeconds(48 * 3600L).toString());
        stubRecovery(operation);
        walletService.recoverPendingDeposit(operation.getReference());
        verify(stripeClient).getCheckoutSession("cs_test_example");
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
        assertEquals(WalletOperationStatus.PENDING, operation.getStatus());
    }

    @Test
    void changedConfigurationCannotChangeRecoveryParameters() {
        WalletOperation operation = pendingDeposit();
        operation.setStripeSessionId(null);
        Map<String, Object> original = new LinkedHashMap<>(operation.getStripeCreationRequest());
        doThrow(new IllegalStateException("Today's configuration is unavailable"))
                .when(stripeClient).prepareCheckoutSession(any());

        walletService.recoverPendingDeposit(operation.getReference());

        verify(stripeClient, never()).prepareCheckoutSession(any());
        verify(stripeClient).createCheckoutSession(eq(original), eq("wallet-deposit:" + operation.getReference()));
    }

    @Test
    void timeoutDuringRecoveryRemainsPendingAndUsesOnlyTheOriginalKey() {
        WalletOperation operation = pendingDeposit();
        operation.setStripeSessionId(null);
        doThrow(new IllegalStateException("Unknown Stripe creation outcome"))
                .when(stripeClient).createCheckoutSession(anyMap(), anyString());

        DepositResponse first = walletService.recoverPendingDeposit(operation.getReference());
        assertEquals(first, walletService.recoverPendingDeposit(operation.getReference()));
        assertNull(operation.getStripeSessionId());
        verify(stripeClient, times(2)).createCheckoutSession(eq(operation.getStripeCreationRequest()),
                eq("wallet-deposit:" + operation.getReference()));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
    }

    @Test
    void corruptedSavedReferenceCannotCreateAProviderSession() {
        WalletOperation operation = pendingDeposit();
        operation.setStripeSessionId(null);
        Map<String, Object> saved = operation.getStripeCreationRequest();
        Map<Object, Object> parameters = new LinkedHashMap<>((Map<?, ?>) saved.get("parameters"));
        parameters.put("client_reference_id", UUID.randomUUID().toString());
        saved.put("parameters", parameters);
        assertThrows(BusinessRuleException.class,
                () -> walletService.recoverPendingDeposit(operation.getReference()));
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
    }

    @Test
    void historicalMyFatoorahDepositRemainsReadableWithoutChangingItsProviderDetails() {
        WalletOperation operation = pendingDeposit();
        operation.setPaymentMethod(SimulatedPaymentMethod.MYFATOORAH_SANDBOX);
        operation.setStripeSessionId(null);
        operation.setStripeCreationRequest(null);
        operation.setProviderInvoiceId(7366628L);
        operation.setProviderInvoiceStatus("Paid");
        operation.setProviderTransactionStatus("Succss");
        operation.setPaymentUrl("https://demo.myfatoorah.com/original");

        DepositResponse response = walletService.recoverPendingDeposit(operation.getReference());
        assertEquals(response, walletService.createPendingDeposit(request("10.00"), operation.getIdempotencyKey()));

        assertEquals(7366628L, operation.getProviderInvoiceId());
        assertEquals("Paid", operation.getProviderInvoiceStatus());
        assertEquals("Succss", operation.getProviderTransactionStatus());
        assertEquals("https://demo.myfatoorah.com/original", response.paymentUrl());
        assertEquals(SimulatedPaymentMethod.MYFATOORAH_SANDBOX, operation.getPaymentMethod());
        verify(stripeClient, never()).createCheckoutSession(anyMap(), anyString());
        verify(stripeClient, never()).getCheckoutSession(anyString());
        verify(walletOperationRepository, never()).save(any());
    }

    @Test
    void concurrentCreationAndRecoveryUseIdenticalStripeRequestsAndKeys() throws Exception {
        CountDownLatch creationStarted = new CountDownLatch(1);
        CountDownLatch releaseCreation = new CountDownLatch(1);
        AtomicLong calls = new AtomicLong();
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(portfolioLock.isHeldByCurrentThread());
            if (calls.incrementAndGet() == 1) {
                creationStarted.countDown();
                assertTrue(releaseCreation.await(5, TimeUnit.SECONDS));
            }
            return providerResponse(call.getArgument(0));
        }).when(stripeClient).createCheckoutSession(anyMap(), anyString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DepositResponse> first = executor.submit(() -> {
                authenticate();
                try { return walletService.createPendingDeposit(request("10.00"), "deposit-1"); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(creationStarted.await(5, TimeUnit.SECONDS));
            WalletOperation operation = operations.get("deposit-1");
            DepositResponse recovered = walletService.recoverPendingDeposit(operation.getReference());
            releaseCreation.countDown();
            assertEquals(recovered, first.get(5, TimeUnit.SECONDS));
            verify(stripeClient, times(2)).createCheckoutSession(eq(operation.getStripeCreationRequest()),
                    eq("wallet-deposit:" + operation.getReference()));
            assertEquals(1, operations.size());
            assertEquals("cs_test_example", operation.getStripeSessionId());
            assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
            assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
        } finally {
            releaseCreation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void verifiesTenDollarDepositOnlyOnce() {
        WalletOperation operation = pendingDeposit();
        stubVerification(operation);
        assertEquals(WalletOperationStatus.COMPLETED, walletService.verifyPendingDeposit(operation.getReference()).status());
        assertEquals(new BigDecimal("1010.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
        assertEquals("pi_example", operation.getProviderPaymentId());
        assertEquals("complete", operation.getProviderInvoiceStatus());
        assertEquals("succeeded", operation.getProviderTransactionStatus());
        assertEquals(portfolio.getCashBalance(), operation.getBalanceAfter());
        assertNotNull(operation.getCompletedAt());
        var completedAt = operation.getCompletedAt();
        walletService.verifyPendingDeposit(operation.getReference());
        assertEquals(completedAt, operation.getCompletedAt());
        assertEquals(new BigDecimal("1010.00"), portfolio.getCashBalance());
        verify(stripeClient, times(1)).getCheckoutSession(anyString());
        verify(portfolioRepository, times(1)).save(portfolio);
        assertEquals(List.of(new WalletCompletedEvent(42L, operation.getReference(), "DEPOSIT", new BigDecimal("10.00"), "COMPLETED")), completedEvents);
        verify(investmentEventService, times(1)).saveWalletEvent(any());
    }

    @Test
    void concurrentVerificationCreditsOnlyOnce() throws Exception {
        WalletOperation operation = pendingDeposit();
        stubVerification(operation);
        CyclicBarrier requests = new CyclicBarrier(2);
        when(stripeClient.getPaymentIntent("pi_example")).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            requests.await(5, TimeUnit.SECONDS);
            return verifiedPayment(operation);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<DepositResponse> verifyDeposit = () -> {
                authenticate();
                try { return walletService.verifyPendingDeposit(operation.getReference()); }
                finally { SecurityContextHolder.clearContext(); }
            };
            Future<DepositResponse> first = executor.submit(verifyDeposit);
            Future<DepositResponse> second = executor.submit(verifyDeposit);
            assertEquals(WalletOperationStatus.COMPLETED, first.get(10, TimeUnit.SECONDS).status());
            assertEquals(WalletOperationStatus.COMPLETED, second.get(10, TimeUnit.SECONDS).status());
            assertEquals(new BigDecimal("1010.00"), portfolio.getCashBalance());
            verify(portfolioRepository, times(1)).save(portfolio);
            assertEquals(1, completedEvents.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unpaidVerificationDoesNotCredit() {
        WalletOperation operation = pendingDeposit();
        when(stripeClient.getCheckoutSession(anyString())).thenReturn(
                recoveryResponse(operation.getReference().toString(), "10.00 USD", "Pending"));
        assertEquals(WalletOperationStatus.PENDING, walletService.verifyPendingDeposit(operation.getReference()).status());
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        verify(stripeClient, never()).getPaymentIntent(anyString());
        verify(portfolioRepository, never()).save(any());
        verifyNoInteractions(investmentEventService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "currency", "amount", "reference", "metadata", "live", "mode", "missing"})
    void rejectsConflictingSession(String field) {
        WalletOperation operation = pendingDeposit();
        StripeCheckoutSessionResponse session = recoveryResponse(operation.getReference().toString(), "10.00 USD", "Paid");
        when(stripeClient.getCheckoutSession(anyString())).thenReturn(field.equals("missing") ? null :
                new StripeCheckoutSessionResponse(field.equals("session") ? "cs_test_other" : session.id(), session.object(),
                        field.equals("amount") ? 999L : session.amountTotal(), field.equals("currency") ? "kwd" : "usd",
                        field.equals("mode") ? "subscription" : "payment", "complete", "paid", field.equals("live"),
                        field.equals("reference") ? "other" : session.clientReferenceId(),
                        field.equals("metadata") ? Map.of() : session.metadata(), session.paymentIntent(), null, null));
        assertThrows(RuntimeException.class, () -> walletService.verifyPendingDeposit(operation.getReference()));
        assertEquals(WalletOperationStatus.PENDING, operation.getStatus());
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        verify(portfolioRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "currency", "amount", "received", "metadata", "live", "status", "missing", "saved-id"})
    void rejectsConflictingPaymentIntent(String field) {
        WalletOperation operation = pendingDeposit();
        stubVerification(operation);
        if (field.equals("saved-id")) operation.setProviderPaymentId("pi_other");
        when(stripeClient.getPaymentIntent(anyString())).thenReturn(field.equals("missing") ? null :
                new StripePaymentIntentResponse(field.equals("id") ? "pi_other" : "pi_example", "payment_intent",
                        field.equals("status") ? "processing" : "succeeded", field.equals("currency") ? "kwd" : "usd",
                        field.equals("amount") ? 999L : 1000L, field.equals("received") ? 999L : 1000L,
                        field.equals("live"), field.equals("metadata") ? Map.of() : Map.of("wallet_reference", operation.getReference().toString())));
        assertThrows(RuntimeException.class, () -> walletService.verifyPendingDeposit(operation.getReference()));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertEquals(WalletOperationStatus.PENDING, operation.getStatus());
        verify(portfolioRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"session-error", "session-timeout", "payment-error", "payment-timeout"})
    void providerFailureDoesNotCredit(String failure) {
        WalletOperation operation = pendingDeposit();
        stubVerification(operation);
        IllegalStateException error = new IllegalStateException(failure);
        if (failure.startsWith("session")) {
            when(stripeClient.getCheckoutSession(anyString())).thenThrow(error);
        } else {
            when(stripeClient.getPaymentIntent(anyString())).thenThrow(error);
        }
        assertThrows(IllegalStateException.class, () -> walletService.verifyPendingDeposit(operation.getReference()));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertEquals(WalletOperationStatus.PENDING, operation.getStatus());
        verify(portfolioRepository, never()).save(any());
    }

    @Test
    void saveFailureRollsBackTheCreditTransaction() {
        WalletOperation operation = pendingDeposit();
        stubVerification(operation);
        AtomicLong committedCredits = new AtomicLong();
        when(portfolioRepository.save(portfolio)).thenAnswer(call -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { committedCredits.incrementAndGet(); }
            });
            return portfolio;
        });
        doThrow(new IllegalStateException("Database save failed")).when(walletOperationRepository).save(operation);
        assertThrows(IllegalStateException.class, () -> walletService.verifyPendingDeposit(operation.getReference()));
        assertEquals(0, committedCredits.get());
        assertEquals(1, transactionManager.rollbacks);
        // this checks rollback calls, not real database changes
    }

    @Test
    void verificationRejectsOtherOwnersAndLegacyDeposits() {
        WalletOperation operation = pendingDeposit();
        assertThrows(InformationNotFoundException.class, () -> walletService.verifyPendingDeposit(UUID.randomUUID()));
        operation.setPaymentMethod(SimulatedPaymentMethod.MYFATOORAH_SANDBOX);
        assertThrows(BusinessRuleException.class, () -> walletService.verifyPendingDeposit(operation.getReference()));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        verify(stripeClient, never()).getCheckoutSession(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED", "WITHDRAWAL", "owner", "missing-session"})
    void verificationRejectsIneligibleOperations(String change) {
        WalletOperation operation = pendingDeposit();
        if (change.equals("FAILED")) operation.setStatus(WalletOperationStatus.FAILED);
        if (change.equals("CANCELLED")) operation.setStatus(WalletOperationStatus.CANCELLED);
        if (change.equals("WITHDRAWAL")) operation.setOperationType(WalletOperationType.WITHDRAWAL);
        if (change.equals("owner")) portfolio.setUserId(99L);
        if (change.equals("missing-session")) operation.setStripeSessionId(null);
        assertThrows(RuntimeException.class, () -> walletService.verifyPendingDeposit(operation.getReference()));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        verify(stripeClient, never()).getCheckoutSession(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "amount", "status", "provider"})
    void rechecksDepositAfterProviderRequests(String change) {
        WalletOperation operation = pendingDeposit();
        stubVerification(operation);
        when(stripeClient.getPaymentIntent(anyString())).thenAnswer(call -> {
            if (change.equals("session")) operation.setStripeSessionId("cs_test_other");
            if (change.equals("amount")) operation.setAmount(new BigDecimal("20.00"));
            if (change.equals("status")) operation.setStatus(WalletOperationStatus.CANCELLED);
            if (change.equals("provider")) operation.setPaymentMethod(SimulatedPaymentMethod.MYFATOORAH_SANDBOX);
            return verifiedPayment(operation);
        });
        assertThrows(RuntimeException.class, () -> walletService.verifyPendingDeposit(operation.getReference()));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        verify(portfolioRepository, never()).save(any());
    }

    @Test
    void verificationCannotRunInsideAnExistingTransaction() {
        WalletOperation operation = pendingDeposit();
        new TransactionTemplate(transactionManager).execute(transaction -> {
            assertThrows(IllegalStateException.class, () -> walletService.verifyPendingDeposit(operation.getReference()));
            return null;
        });
        verify(stripeClient, never()).getCheckoutSession(anyString());
    }

    private void stubVerification(WalletOperation operation) {
        stubRecovery(operation);
        when(stripeClient.getPaymentIntent("pi_example")).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(portfolioLock.isHeldByCurrentThread());
            return verifiedPayment(operation);
        });
    }

    private StripePaymentIntentResponse verifiedPayment(WalletOperation operation) {
        return new StripePaymentIntentResponse("pi_example", "payment_intent", "succeeded", "usd", 1000L, 1000L,
                false, Map.of("wallet_reference", operation.getReference().toString()));
    }

    @Test
    void withdrawsCashAndRecordsCompletedHistory() {
        stubWithdrawalInsert();
        var response = walletService.withdrawCash(withdrawal("200.00"), "withdraw-1");
        assertEquals(WalletOperationStatus.COMPLETED, response.status());
        assertEquals(new BigDecimal("800.00"), response.balanceAfter());
        assertEquals(new BigDecimal("800.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
        WalletOperation operation = operations.get("withdraw-1");
        assertEquals(response.reference(), operation.getReference());
        assertEquals(WalletOperationType.WITHDRAWAL, operation.getOperationType());
        assertEquals("USD", operation.getCurrency());
        assertNull(operation.getPaymentMethod());
        verifyNoInteractions(stripeClient);
        assertEquals(List.of(new WalletCompletedEvent(42L, response.reference(), "WITHDRAWAL", new BigDecimal("200.00"), "COMPLETED")), completedEvents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "10.000", "10000.01", "10000000000000"})
    void rejectsInvalidWithdrawalAmounts(String amount) {
        assertThrows(BusinessRuleException.class, () -> walletService.withdrawCash(withdrawal(amount), "withdraw-1"));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertTrue(operations.isEmpty());
    }

    @Test
    void rejectsMissingWithdrawalAmountAndAuthentication() {
        assertThrows(BusinessRuleException.class, () -> walletService.withdrawCash(null, "withdraw-1"));
        assertThrows(BusinessRuleException.class, () -> walletService.withdrawCash(new WithdrawalRequest(), "withdraw-1"));
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> walletService.withdrawCash(withdrawal("10.00"), "withdraw-1"));
        verify(portfolioRepository, never()).findByUserId(anyLong());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingWithdrawalKey(String key) {
        assertThrows(BusinessRuleException.class, () -> walletService.withdrawCash(withdrawal("10.00"), key));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
    }

    @Test
    void rejectsOversizedWithdrawalKey() {
        assertThrows(BusinessRuleException.class,
                () -> walletService.withdrawCash(withdrawal("10.00"), "x".repeat(129)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"800.01", "1000.00", "1001.00"})
    void protectsReservedCash(String amount) {
        assertThrows(BusinessRuleException.class, () -> walletService.withdrawCash(withdrawal(amount), "withdraw-1"));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
        verify(portfolioRepository, never()).save(any());
    }

    @Test
    void canWithdrawExactlyAvailableCash() {
        stubWithdrawalInsert();
        walletService.withdrawCash(withdrawal("800.00"), "withdraw-1");
        assertEquals(new BigDecimal("200.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
    }

    @Test
    void duplicateWithdrawalReturnsOriginalBalanceAndDoesNotDeductAgain() {
        stubWithdrawalInsert();
        var first = walletService.withdrawCash(withdrawal("200.00"), "withdraw-1");
        // keep retries valid if the limit changes
        ReflectionTestUtils.setField(walletService, "maxWithdrawalAmount", new BigDecimal("100.00"));
        var second = walletService.withdrawCash(withdrawal("200"), "withdraw-1");
        assertEquals(first, second);
        assertEquals(new BigDecimal("800.00"), portfolio.getCashBalance());
        verify(portfolioRepository, times(1)).save(portfolio);
        assertEquals(1, completedEvents.size());
        verify(investmentEventService, times(1)).saveWalletEvent(any());
    }

    @Test
    void conflictsOnDifferentWithdrawalAmountOrDepositKey() {
        stubWithdrawalInsert();
        walletService.withdrawCash(withdrawal("200.00"), "withdraw-1");
        assertThrows(InformationExistException.class,
                () -> walletService.withdrawCash(withdrawal("201.00"), "withdraw-1"));
        WalletOperation deposit = pendingDeposit();
        assertThrows(InformationExistException.class,
                () -> walletService.withdrawCash(withdrawal("10.00"), deposit.getIdempotencyKey()));
        assertEquals(new BigDecimal("800.00"), portfolio.getCashBalance());
    }

    @Test
    void withdrawalUsesConfiguredLimitAndChecksLockedOwner() {
        ReflectionTestUtils.setField(walletService, "maxWithdrawalAmount", new BigDecimal("100.00"));
        assertThrows(BusinessRuleException.class, () -> walletService.withdrawCash(withdrawal("100.01"), "withdraw-1"));
        portfolio.setUserId(99L);
        assertThrows(InformationNotFoundException.class,
                () -> walletService.withdrawCash(withdrawal("10.00"), "withdraw-1"));
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentWithdrawalsAreSerialized(boolean sameKey) throws Exception {
        stubWithdrawalInsert();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> first = () -> concurrentWithdrawal(start, "withdraw-1");
        Callable<Boolean> second = () -> concurrentWithdrawal(start, sameKey ? "withdraw-1" : "withdraw-2");
        try {
            Future<Boolean> a = executor.submit(first);
            Future<Boolean> b = executor.submit(second);
            start.countDown();
            boolean firstSucceeded = a.get(5, TimeUnit.SECONDS);
            boolean secondSucceeded = b.get(5, TimeUnit.SECONDS);
            if (sameKey) {
                assertTrue(firstSucceeded && secondSucceeded);
            } else {
                assertNotEquals(firstSucceeded, secondSucceeded);
            }
            assertEquals(1, operations.size());
            assertEquals(1, completedEvents.size());
            assertEquals(new BigDecimal("400.00"), portfolio.getCashBalance());
            assertEquals(new BigDecimal("200.00"), portfolio.getReservedCash());
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void withdrawalInsertFailureRollsBackTransaction() {
        AtomicLong committedCashUpdates = new AtomicLong();
        when(portfolioRepository.save(portfolio)).thenAnswer(call -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { committedCashUpdates.incrementAndGet(); }
            });
            return portfolio;
        });
        when(walletOperationRepository.insertCompletedWithdrawal(anyLong(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("Database insert failed"));
        assertThrows(IllegalStateException.class,
                () -> walletService.withdrawCash(withdrawal("200.00"), "withdraw-1"));
        assertEquals(1, transactionManager.rollbacks);
        assertEquals(0, committedCashUpdates.get());
        assertTrue(operations.isEmpty());
        // this checks rollback calls, not real database changes
    }

    private boolean concurrentWithdrawal(CountDownLatch start, String key) throws Exception {
        authenticate();
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            walletService.withdrawCash(withdrawal("600.00"), key);
            return true;
        } catch (BusinessRuleException e) {
            return false;
        } finally { SecurityContextHolder.clearContext(); }
    }

    private WithdrawalRequest withdrawal(String amount) {
        WithdrawalRequest request = new WithdrawalRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        return request;
    }

    private void stubWithdrawalInsert() {
        when(walletOperationRepository.insertCompletedWithdrawal(anyLong(), any(), anyString(), any(), any()))
                .thenAnswer(call -> {
                    assertTrue(portfolioLock.isHeldByCurrentThread());
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals(5L, (Long) call.getArgument(0));
                    WalletOperation operation = new WalletOperation();
                    operation.setReference(call.getArgument(1));
                    operation.setIdempotencyKey(call.getArgument(2));
                    operation.setAmount(call.getArgument(3));
                    operation.setBalanceAfter(call.getArgument(4));
                    operation.setPortfolio(portfolio);
                    operation.setOperationType(WalletOperationType.WITHDRAWAL);
                    operation.setStatus(WalletOperationStatus.COMPLETED);
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() { operations.put(operation.getIdempotencyKey(), operation); }
                    });
                    return 1;
                });
    }

    @Test
    void walletReadsAreOwnerScopedAndDoNotChangeBalances() {
        WalletOperation operation = pendingDeposit();
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        when(walletOperationRepository.findByPortfolio_IdOrderByCreatedAtDescIdDesc(5L, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(operation)));
        var balance = walletService.getWalletBalance();
        var history = walletService.getWalletHistory(0, 10);
        assertEquals(new BigDecimal("1000.00"), balance.cashBalance());
        assertEquals(new BigDecimal("200.00"), balance.reservedCash());
        assertEquals(new BigDecimal("800.00"), balance.availableCash());
        assertEquals(operation.getReference(), history.getContent().get(0).reference());
        verify(portfolioRepository, times(2)).findByUserId(42L);
        verify(walletOperationRepository).findByPortfolio_IdOrderByCreatedAtDescIdDesc(5L, pageable);
        verify(portfolioRepository, never()).save(any());
        verify(walletOperationRepository, never()).save(any());
        verifyNoInteractions(stripeClient);
        assertEquals(new BigDecimal("1000.00"), portfolio.getCashBalance());
    }

    @Test
    void walletReadsRejectMissingIdentityAndPortfolio() {
        when(portfolioRepository.findByUserId(42L)).thenReturn(Optional.empty());
        assertThrows(InformationNotFoundException.class, () -> walletService.getWalletBalance());
        assertThrows(InformationNotFoundException.class, () -> walletService.getWalletHistory(0, 10));
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> walletService.getWalletBalance());
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> walletService.getWalletHistory(0, 10));
    }

    @Test
    void serviceRejectsInvalidWalletPagination() {
        assertThrows(BusinessRuleException.class, () -> walletService.getWalletHistory(-1, 10));
        assertThrows(BusinessRuleException.class, () -> walletService.getWalletHistory(0, 0));
        assertThrows(BusinessRuleException.class, () -> walletService.getWalletHistory(0, 101));
        verifyNoInteractions(walletOperationRepository);
    }

    @Test
    void creationResultsDistinguishIdempotentReplays() {
        var deposit = walletService.createPendingDepositResult(request("10.00"), "deposit-1");
        var replay = walletService.createPendingDepositResult(request("10.00"), "deposit-1");
        assertTrue(deposit.created());
        assertFalse(replay.created());
        assertEquals(deposit.response(), replay.response());
        stubWithdrawalInsert();
        var withdrawal = walletService.withdrawCashResult(withdrawal("100.00"), "withdraw-1");
        var repeated = walletService.withdrawCashResult(withdrawal("100.00"), "withdraw-1");
        assertTrue(withdrawal.created());
        assertFalse(repeated.created());
        assertEquals(withdrawal.response(), repeated.response());
    }

    @Test
    void recoveryDoesNotSendACompletionEventEvenWhenStripeIsPaid() {
        WalletOperation operation = pendingDeposit();
        stubRecovery(operation);
        walletService.recoverPendingDeposit(operation.getReference());
        assertEquals(WalletOperationStatus.PENDING, operation.getStatus());
        verifyNoInteractions(investmentEventService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPOSIT", "WITHDRAWAL"})
    void outboxSaveFailureRollsBackWalletCompletion(String type) {
        doThrow(new IllegalStateException("Outbox save failed")).when(investmentEventService).saveWalletEvent(any());
        if (type.equals("DEPOSIT")) {
            WalletOperation operation = pendingDeposit();
            stubVerification(operation);
            assertThrows(IllegalStateException.class, () -> walletService.verifyPendingDeposit(operation.getReference()));
        } else {
            stubWithdrawalInsert();
            assertThrows(IllegalStateException.class, () -> walletService.withdrawCash(withdrawal("10.00"), "withdraw-1"));
            assertTrue(operations.isEmpty());
        }
        assertEquals(1, transactionManager.rollbacks);
        assertTrue(completedEvents.isEmpty());
    }

    private WalletOperation pendingDeposit() {
        WalletOperation operation = new WalletOperation();
        operation.setId(ids.incrementAndGet());
        operation.setPortfolio(portfolio);
        operation.setReference(UUID.randomUUID());
        operation.setIdempotencyKey("recover-1");
        operation.setAmount(new BigDecimal("10.00"));
        operation.setCurrency("USD");
        operation.setStatus(WalletOperationStatus.PENDING);
        operation.setOperationType(WalletOperationType.DEPOSIT);
        operation.setPaymentMethod(SimulatedPaymentMethod.STRIPE_SANDBOX);
        operation.setStripeSessionId("cs_test_example");
        Map<String, Object> saved = new LinkedHashMap<>(requestBuilder.prepareCheckoutSession(
                new StripeCheckoutRequest(operation.getAmount(), operation.getReference(), null, null)));
        saved.put("createdAt", now.minusSeconds(60).toString());
        operation.setStripeCreationRequest(saved);
        operations.put(operation.getIdempotencyKey(), operation);
        return operation;
    }

    private void stubRecovery(WalletOperation operation) {
        when(stripeClient.getCheckoutSession(anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(portfolioLock.isHeldByCurrentThread());
            return recoveryResponse(operation.getReference().toString(), "10.000 USD", "Paid");
        });
    }

    private StripeCheckoutSessionResponse recoveryResponse(String reference, String amount, String status) {
        return new StripeCheckoutSessionResponse("cs_test_example", "checkout.session",
                new BigDecimal(amount.replace(" USD", "")).movePointRight(2).longValueExact(), "usd", "payment",
                status.equals("Paid") ? "complete" : "open", status.equals("Paid") ? "paid" : "unpaid",
                false, reference, Map.of("wallet_reference", reference),
                status.equals("Paid") ? "pi_example" : null, null, 1794578807L);
    }

    private void authenticate() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(42L, "investor@example.com", "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
    }

    private DepositRequest request(String amount) {
        DepositRequest request = new DepositRequest();
        ReflectionTestUtils.setField(request, "amount", new BigDecimal(amount));
        return request;
    }

    private StripeCheckoutSessionResponse providerResponse(Map<String, Object> request) {
        Map<?, ?> parameters = (Map<?, ?>) request.get("parameters");
        String reference = (String) parameters.get("client_reference_id");
        return new StripeCheckoutSessionResponse("cs_test_example", "checkout.session",
                Long.parseLong((String) parameters.get("line_items[0][price_data][unit_amount]")), "usd", "payment",
                "open", "unpaid", false, reference, Map.of("wallet_reference", reference), null,
                "https://checkout.stripe.com/c/pay/example", 1794578807L);
    }

    // use spring transactions with mocked repositories
    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        private boolean failCommit;
        private int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new IllegalStateException("Commit failed");
            }
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
