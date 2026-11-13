package com.ga.investmentservice.Service;

import com.ga.investmentservice.Client.StripeClient;
import com.ga.investmentservice.DTO.Request.DepositRequest;
import com.ga.investmentservice.DTO.Request.WithdrawalRequest;
import com.ga.investmentservice.DTO.Request.StripeCheckoutRequest;
import com.ga.investmentservice.DTO.Response.DepositResponse;
import com.ga.investmentservice.DTO.Response.WithdrawalResponse;
import com.ga.investmentservice.DTO.Response.WalletBalanceResponse;
import com.ga.investmentservice.DTO.Response.WalletHistoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import com.ga.investmentservice.DTO.Response.StripeCheckoutSessionResponse;
import com.ga.investmentservice.DTO.Response.StripePaymentIntentResponse;
import com.ga.investmentservice.Enums.SimulatedPaymentMethod;
import com.ga.investmentservice.Enums.WalletOperationStatus;
import com.ga.investmentservice.Enums.WalletOperationType;
import com.ga.investmentservice.Exception.BusinessRuleException;
import com.ga.investmentservice.Exception.InformationExistException;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.WalletOperation;
import com.ga.investmentservice.Repository.PortfolioRepository;
import com.ga.investmentservice.Repository.WalletOperationRepository;
import com.ga.investmentservice.Security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.DateTimeException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class WalletService {
    private final PortfolioRepository portfolioRepository;
    private final WalletOperationRepository walletOperationRepository;
    private final CurrentUser currentUser;
    private final StripeClient stripeClient;
    private final TransactionTemplate transactionTemplate;
    private final BigDecimal maxDepositAmount;
    private final int retryWindowHours;
    @Value("${wallet.withdrawal.max-amount:10000.00}")
    private BigDecimal maxWithdrawalAmount = new BigDecimal("10000.00");
    private Clock clock = Clock.systemUTC();

    @PersistenceContext
    private EntityManager entityManager;

    public WalletService(PortfolioRepository portfolioRepository,
                         WalletOperationRepository walletOperationRepository,
                         CurrentUser currentUser,
                         StripeClient stripeClient,
                         PlatformTransactionManager transactionManager,
                         @Value("${wallet.deposit.max-amount}") BigDecimal maxDepositAmount,
                         @Value("${wallet.deposit.stripe-retry-window-hours}") int retryWindowHours) {
        this.portfolioRepository = portfolioRepository;
        this.walletOperationRepository = walletOperationRepository;
        this.currentUser = currentUser;
        this.stripeClient = stripeClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxDepositAmount = maxDepositAmount;
        this.retryWindowHours = retryWindowHours;

        if (maxDepositAmount == null || maxDepositAmount.signum() <= 0
                || maxDepositAmount.compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw new IllegalArgumentException("Invalid maximum deposit amount");
        }
        // stop retries before stripe may remove the key after 24 hours
        if (retryWindowHours <= 0 || retryWindowHours > 23) {
            throw new IllegalArgumentException("Stripe retry window must be between 1 and 23 hours");
        }
    }

    //create the pending operation before calling the payment provider
    @Transactional(propagation = Propagation.NEVER)
    @PreAuthorize("hasRole('USER')")
    public DepositResponse createPendingDeposit(DepositRequest request, String idempotencyKey) {
        return createPendingDepositResult(request, idempotencyKey).response();
    }

    @Transactional(propagation = Propagation.NEVER)
    @PreAuthorize("hasRole('USER')")
    public WalletResult<DepositResponse> createPendingDepositResult(DepositRequest request, String idempotencyKey) {
        // call stripe outside database transactions
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Deposit creation cannot run inside an existing transaction");
        }
        Long userId = currentUser.getUserId();

        if (request == null || request.getAmount() == null || request.getAmount().signum() <= 0
                || request.getAmount().scale() > 2
                || request.getAmount().compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw new BusinessRuleException("Deposit amount must be positive USD with at most two decimal places");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessRuleException("Idempotency-Key is required and must not exceed 128 characters");
        }

        BigDecimal amount = request.getAmount().setScale(2);
        UUID reference = UUID.randomUUID();
        PendingDeposit pending = transactionTemplate.execute(transaction -> {
            Portfolio portfolio = lockPortfolio(userId);
            var existing = walletOperationRepository.findByPortfolio_IdAndIdempotencyKey(portfolio.getId(), idempotencyKey);

            if (existing.isPresent()) {
                WalletOperation operation = existing.get();
                if (operation.getOperationType() != WalletOperationType.DEPOSIT
                        || operation.getAmount().compareTo(amount) != 0
                        || !"USD".equals(operation.getCurrency())
                        || (operation.getPaymentMethod() != SimulatedPaymentMethod.STRIPE_SANDBOX
                            && operation.getPaymentMethod() != SimulatedPaymentMethod.MYFATOORAH_SANDBOX)) {
                    throw new InformationExistException("Idempotency-Key has already been used for a different request");
                }
                return snapshot(operation);
            }

            if (amount.compareTo(maxDepositAmount) > 0) {
                throw new BusinessRuleException("Deposit amount exceeds the configured limit");
            }
            // prepare the request without calling stripe
            Map<String, Object> savedRequest = new LinkedHashMap<>(stripeClient.prepareCheckoutSession(
                    new StripeCheckoutRequest(amount, reference, null, null)));
            savedRequest.put("createdAt", clock.instant().toString());

            WalletOperation operation = new WalletOperation();
            operation.setReference(reference);
            operation.setIdempotencyKey(idempotencyKey);
            operation.setOperationType(WalletOperationType.DEPOSIT);
            operation.setAmount(amount);
            operation.setCurrency("USD");
            operation.setPaymentMethod(SimulatedPaymentMethod.STRIPE_SANDBOX);
            operation.setStatus(WalletOperationStatus.PENDING);
            operation.setPortfolio(portfolio);
            operation.setStripeCreationRequest(savedRequest);
            walletOperationRepository.save(operation);
            return snapshot(operation);
        });

        // reuse the saved operation for duplicate requests
        if (!reference.equals(pending.reference()) || Thread.currentThread().isInterrupted() || !canRetry(pending)) {
            return new WalletResult<>(pending.response(), reference.equals(pending.reference()));
        }
        validateSavedRequest(pending);
        StripeCheckoutSessionResponse response;
        try {
            response = stripeClient.createCheckoutSession(pending.creationRequest(), stripeKey(reference));
        } catch (IllegalStateException | IllegalArgumentException e) {
            // keep unknown results pending and recover with the same request
            return new WalletResult<>(pending.response(), reference.equals(pending.reference()));
        }
        validateSession(pending, response);
        return new WalletResult<>(saveSession(userId, pending, response), true);
    }

    // recover checkout details without adding cash
    @Transactional(propagation = Propagation.NEVER)
    @PreAuthorize("hasRole('USER')")
    public DepositResponse recoverPendingDeposit(UUID reference) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Deposit recovery cannot run inside an existing transaction");
        }
        Long userId = currentUser.getUserId();
        if (reference == null) {
            throw new BusinessRuleException("Wallet operation reference is required");
        }
        PendingDeposit pending = transactionTemplate.execute(transaction -> snapshot(lockPendingDeposit(userId, reference)));
        // leave old provider operations unchanged
        if (pending.method() == SimulatedPaymentMethod.MYFATOORAH_SANDBOX || Thread.currentThread().isInterrupted()) {
            return pending.response();
        }

        StripeCheckoutSessionResponse response;
        if (pending.sessionId() != null) {
            try {
                response = stripeClient.getCheckoutSession(pending.sessionId());
            } catch (IllegalStateException | IllegalArgumentException e) {
                return pending.response();
            }
        } else {
            // do not retry creation after the safe retry window
            if (!canRetry(pending)) {
                return pending.response();
            }
            validateSavedRequest(pending);
            try {
                response = stripeClient.createCheckoutSession(pending.creationRequest(), stripeKey(reference));
            } catch (IllegalStateException | IllegalArgumentException e) {
                return pending.response();
            }
        }
        validateSession(pending, response);
        return saveSession(userId, pending, response);
    }

    @Transactional(propagation = Propagation.NEVER)
    @PreAuthorize("hasRole('USER')")
    public WithdrawalResponse withdrawCash(WithdrawalRequest request, String idempotencyKey) {
        return withdrawCashResult(request, idempotencyKey).response();
    }

    @Transactional(propagation = Propagation.NEVER)
    @PreAuthorize("hasRole('USER')")
    public WalletResult<WithdrawalResponse> withdrawCashResult(WithdrawalRequest request, String idempotencyKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Withdrawal cannot run inside an existing transaction");
        }
        Long userId = currentUser.getUserId();
        if (request == null || request.getAmount() == null || request.getAmount().signum() <= 0
                || request.getAmount().scale() > 2
                || request.getAmount().compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw new BusinessRuleException("Withdrawal amount must be positive USD with at most two decimal places");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessRuleException("Idempotency-Key is required and must not exceed 128 characters");
        }
        if (maxWithdrawalAmount == null || maxWithdrawalAmount.signum() <= 0
                || maxWithdrawalAmount.compareTo(new BigDecimal("9999999999999.99")) > 0) {
            throw new IllegalStateException("Invalid maximum withdrawal amount");
        }
        BigDecimal amount = request.getAmount().setScale(2);
        return transactionTemplate.execute(transaction -> {
            Portfolio portfolio = lockPortfolio(userId);
            // read the latest cash after locking the portfolio
            entityManager.refresh(portfolio);
            if (!userId.equals(portfolio.getUserId())) {
                throw new InformationNotFoundException("Portfolio does not exist");
            }
            var existing = walletOperationRepository.findByPortfolio_IdAndIdempotencyKey(portfolio.getId(), idempotencyKey);
            if (existing.isPresent()) {
                WalletOperation operation = existing.get();
                if (operation.getOperationType() != WalletOperationType.WITHDRAWAL
                        || operation.getAmount().compareTo(amount) != 0 || !"USD".equals(operation.getCurrency())
                        || operation.getStatus() != WalletOperationStatus.COMPLETED) {
                    throw new InformationExistException("Idempotency-Key has already been used for a different request");
                }
                return new WalletResult<>(new WithdrawalResponse(operation.getReference(), operation.getStatus(), operation.getBalanceAfter()), false);
            }
            if (amount.compareTo(maxWithdrawalAmount) > 0) {
                throw new BusinessRuleException("Withdrawal amount exceeds the configured limit");
            }
            BigDecimal availableCash = portfolio.getCashBalance().subtract(portfolio.getReservedCash());
            if (amount.compareTo(availableCash) > 0 || amount.compareTo(portfolio.getCashBalance()) > 0) {
                throw new BusinessRuleException("Insufficient available cash for withdrawal");
            }
            BigDecimal balance = portfolio.getCashBalance().subtract(amount);
            UUID reference = UUID.randomUUID();
            portfolio.setCashBalance(balance);
            portfolioRepository.save(portfolio);
            if (walletOperationRepository.insertCompletedWithdrawal(portfolio.getId(), reference, idempotencyKey, amount, balance) != 1) {
                throw new IllegalStateException("Withdrawal history could not be saved");
            }
            return new WalletResult<>(new WithdrawalResponse(reference, WalletOperationStatus.COMPLETED, balance), true);
        });
    }

    @Transactional(propagation = Propagation.NEVER)
    @PreAuthorize("hasRole('USER')")
    public DepositResponse verifyPendingDeposit(UUID reference) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Deposit verification cannot run inside an existing transaction");
        }
        if (reference == null) {
            throw new BusinessRuleException("Wallet operation reference is required");
        }
        Long userId = currentUser.getUserId();
        PendingDeposit pending = transactionTemplate.execute(transaction -> snapshot(lockStripeDeposit(userId, reference)));
        if (pending.status() == WalletOperationStatus.COMPLETED) {
            return pending.response();
        }
        if (pending.sessionId() == null || pending.sessionId().isBlank()) {
            throw new BusinessRuleException("Recover the Stripe Session before verifying the deposit");
        }

        // finish stripe checks before locking rows to add cash
        StripeCheckoutSessionResponse session = stripeClient.getCheckoutSession(pending.sessionId());
        validateSession(pending, session);
        if (!"checkout.session".equals(session.object())) {
            throw new BusinessRuleException("Invalid Stripe Session response");
        }
        if (!"complete".equals(session.status()) || !"paid".equals(session.paymentStatus())) {
            return pending.response();
        }
        if (session.paymentIntent() == null || !session.paymentIntent().matches("pi_[A-Za-z0-9]+")) {
            throw new BusinessRuleException("Stripe payment identifier is missing");
        }
        StripePaymentIntentResponse payment = stripeClient.getPaymentIntent(session.paymentIntent());
        long cents = pending.amount().movePointRight(2).longValueExact();
        if (payment == null || !"payment_intent".equals(payment.object())
                || !session.paymentIntent().equals(payment.id()) || !Boolean.FALSE.equals(payment.livemode())
                || !"succeeded".equals(payment.status()) || !"usd".equals(payment.currency())
                || !Long.valueOf(cents).equals(payment.amount()) || !Long.valueOf(cents).equals(payment.amountReceived())
                || payment.metadata() == null
                || !reference.toString().equals(payment.metadata().get("wallet_reference"))) {
            throw new BusinessRuleException("Stripe payment does not match the deposit");
        }

        return transactionTemplate.execute(transaction -> {
            WalletOperation operation = lockStripeDeposit(userId, reference);
            if (!pending.sessionId().equals(operation.getStripeSessionId())
                    || pending.amount().compareTo(operation.getAmount()) != 0
                    || (operation.getProviderPaymentId() != null
                        && !payment.id().equals(operation.getProviderPaymentId()))) {
                throw new InformationExistException("Wallet deposit payment details changed");
            }
            if (operation.getStatus() == WalletOperationStatus.COMPLETED) {
                return snapshot(operation).response();
            }
            Portfolio portfolio = operation.getPortfolio();
            // refresh cash in case it was loaded before the lock
            entityManager.refresh(portfolio);
            BigDecimal balance = portfolio.getCashBalance().add(operation.getAmount());
            if (balance.compareTo(new BigDecimal("9999999999999.99")) > 0) {
                throw new BusinessRuleException("Deposit exceeds the supported cash balance");
            }
            portfolio.setCashBalance(balance);
            operation.setProviderPaymentId(payment.id());
            operation.setProviderInvoiceStatus(session.status());
            operation.setProviderTransactionStatus(payment.status());
            operation.setStatus(WalletOperationStatus.COMPLETED);
            operation.setBalanceAfter(balance);
            operation.setCompletedAt(LocalDateTime.now());
            portfolioRepository.save(portfolio);
            walletOperationRepository.save(operation);
            return snapshot(operation).response();
        });
    }

    private WalletOperation lockStripeDeposit(Long userId, UUID reference) {
        Portfolio portfolio = lockPortfolio(userId);
        entityManager.refresh(portfolio);
        if (!userId.equals(portfolio.getUserId())) {
            throw new InformationNotFoundException("Portfolio does not exist");
        }
        WalletOperation operation = walletOperationRepository.findByReferenceAndPortfolio_Id(reference, portfolio.getId())
                .orElseThrow(() -> new InformationNotFoundException("Wallet operation does not exist"));
        operation = walletOperationRepository.findWithLockById(operation.getId())
                .orElseThrow(() -> new InformationNotFoundException("Wallet operation does not exist"));
        entityManager.refresh(operation);
        if (!reference.equals(operation.getReference()) || !portfolio.getId().equals(operation.getPortfolio().getId())) {
            throw new InformationNotFoundException("Wallet operation does not exist");
        }
        if (operation.getOperationType() != WalletOperationType.DEPOSIT
                || operation.getPaymentMethod() != SimulatedPaymentMethod.STRIPE_SANDBOX
                || !"USD".equals(operation.getCurrency())
                || (operation.getStatus() != WalletOperationStatus.PENDING
                    && operation.getStatus() != WalletOperationStatus.COMPLETED)) {
            throw new BusinessRuleException("Only pending or completed USD Stripe deposits can be verified");
        }
        return operation;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('USER')")
    public WalletBalanceResponse getWalletBalance() {
        Portfolio portfolio = portfolioRepository.findByUserId(currentUser.getUserId())
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));
        return new WalletBalanceResponse(portfolio.getCashBalance(), portfolio.getReservedCash(),
                portfolio.getCashBalance().subtract(portfolio.getReservedCash()));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('USER')")
    public Page<WalletHistoryResponse> getWalletHistory(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessRuleException("Page must be nonnegative and size must be between 1 and 100");
        }
        Portfolio portfolio = portfolioRepository.findByUserId(currentUser.getUserId())
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));
        return walletOperationRepository.findByPortfolio_IdOrderByCreatedAtDescIdDesc(portfolio.getId(), PageRequest.of(page, size))
                .map(operation -> new WalletHistoryResponse(operation.getReference(), operation.getOperationType(),
                        operation.getAmount(), operation.getCurrency(), operation.getStatus(), operation.getBalanceAfter(),
                        operation.getCreatedAt(), operation.getCompletedAt()));
    }

    public record WalletResult<T>(T response, boolean created) { }

    private DepositResponse saveSession(Long userId, PendingDeposit pending, StripeCheckoutSessionResponse response) {
        return transactionTemplate.execute(transaction -> {
            WalletOperation operation = lockPendingDeposit(userId, pending.reference());
            PendingDeposit current = snapshot(operation);
            validateSession(current, response);
            if (current.method() != SimulatedPaymentMethod.STRIPE_SANDBOX
                    || !Objects.equals(pending.creationRequest(), current.creationRequest())) {
                throw new InformationExistException("Wallet deposit creation parameters changed");
            }
            operation.setStripeSessionId(response.id());
            // keep the saved checkout link when stripe returns no link
            if (operation.getPaymentUrl() == null && response.url() != null) {
                operation.setPaymentUrl(response.url());
            }
            if (!"paid".equals(operation.getProviderTransactionStatus()) || "paid".equals(response.paymentStatus())) {
                operation.setProviderInvoiceStatus(response.status());
                operation.setProviderTransactionStatus(response.paymentStatus());
            }
            // recovery stays pending until payment verification
            walletOperationRepository.save(operation);
            return new DepositResponse(operation.getReference(), operation.getStatus(), operation.getPaymentUrl());
        });
    }

    private boolean canRetry(PendingDeposit pending) {
        if (pending.creationRequest() == null || !(pending.creationRequest().get("createdAt") instanceof String createdAt)) {
            return false;
        }
        try {
            Instant created = Instant.parse(createdAt);
            Instant now = clock.instant();
            return !now.isBefore(created) && now.isBefore(created.plus(retryWindowHours, ChronoUnit.HOURS));
        } catch (DateTimeException e) {
            return false;
        }
    }

    private void validateSavedRequest(PendingDeposit pending) {
        if (!(pending.creationRequest().get("parameters") instanceof Map<?, ?> parameters)
                || !pending.reference().toString().equals(parameters.get("client_reference_id"))
                || !pending.reference().toString().equals(parameters.get("metadata[wallet_reference]"))
                || !pending.reference().toString().equals(parameters.get("payment_intent_data[metadata][wallet_reference]"))
                || !"usd".equals(parameters.get("line_items[0][price_data][currency]"))
                || !pending.amount().movePointRight(2).toBigIntegerExact().toString()
                    .equals(parameters.get("line_items[0][price_data][unit_amount]"))) {
            throw new BusinessRuleException("Saved Stripe creation request does not match the deposit");
        }
    }

    private void validateSession(PendingDeposit pending, StripeCheckoutSessionResponse response) {
        if (response == null || response.id() == null || !response.id().matches("cs_test_[A-Za-z0-9]+")
                || !Boolean.FALSE.equals(response.livemode()) || !"usd".equals(response.currency())
                || !"payment".equals(response.mode()) || response.amountTotal() == null
                || new BigDecimal(response.amountTotal()).compareTo(pending.amount().movePointRight(2)) != 0
                || !pending.reference().toString().equals(response.clientReferenceId())
                || response.metadata() == null
                || !pending.reference().toString().equals(response.metadata().get("wallet_reference"))) {
            throw new BusinessRuleException("Stripe Session does not match the deposit reference or USD amount");
        }
        if (pending.sessionId() != null && !pending.sessionId().equals(response.id())) {
            throw new InformationExistException("Wallet operation already has a different Stripe Session");
        }
    }

    private WalletOperation lockPendingDeposit(Long userId, UUID reference) {
        Portfolio portfolio = lockPortfolio(userId);
        WalletOperation operation = walletOperationRepository.findByReferenceAndPortfolio_Id(reference, portfolio.getId())
                .orElseThrow(() -> new InformationNotFoundException("Wallet operation does not exist"));
        operation = walletOperationRepository.findWithLockById(operation.getId())
                .orElseThrow(() -> new InformationNotFoundException("Wallet operation does not exist"));
        entityManager.refresh(operation);
        if (!reference.equals(operation.getReference()) || !portfolio.getId().equals(operation.getPortfolio().getId())) {
            throw new InformationNotFoundException("Wallet operation does not exist");
        }
        if (operation.getStatus() != WalletOperationStatus.PENDING
                || operation.getOperationType() != WalletOperationType.DEPOSIT
                || (operation.getPaymentMethod() != SimulatedPaymentMethod.STRIPE_SANDBOX
                    && operation.getPaymentMethod() != SimulatedPaymentMethod.MYFATOORAH_SANDBOX)
                || !"USD".equals(operation.getCurrency())) {
            throw new BusinessRuleException("Only pending USD sandbox deposits can be recovered");
        }
        return operation;
    }

    private Portfolio lockPortfolio(Long userId) {
        Long portfolioId = portfolioRepository.findByUserId(userId)
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist")).getId();
        return portfolioRepository.findByIdForUpdate(portfolioId)
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));
    }

    private PendingDeposit snapshot(WalletOperation operation) {
        Map<String, Object> request = operation.getStripeCreationRequest();
        if (request != null) {
            request = new LinkedHashMap<>(request);
            if (request.get("parameters") instanceof Map<?, ?> parameters) {
                request.put("parameters", new LinkedHashMap<>(parameters));
            }
        }
        // keep copied values while calling stripe
        return new PendingDeposit(operation.getReference(), operation.getAmount(), operation.getStatus(),
                operation.getPaymentMethod(), operation.getStripeSessionId(), operation.getPaymentUrl(), request);
    }

    private String stripeKey(UUID reference) {
        return "wallet-deposit:" + reference;
    }

    private record PendingDeposit(UUID reference, BigDecimal amount, WalletOperationStatus status,
                                  SimulatedPaymentMethod method, String sessionId, String paymentUrl,
                                  Map<String, Object> creationRequest) {
        DepositResponse response() {
            return new DepositResponse(reference, status, paymentUrl);
        }
    }
}
