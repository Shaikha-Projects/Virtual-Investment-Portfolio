package com.ga.investmentservice.Repository;

import com.ga.investmentservice.Model.WalletOperation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.ga.investmentservice.Enums.WalletOperationStatus;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletOperationRepository extends JpaRepository<WalletOperation, Long> {
    //use completion time and a bounded result for statements
    List<WalletOperation> findByPortfolio_IdAndStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAscIdAsc(
            Long portfolioId, WalletOperationStatus status, LocalDateTime from, LocalDateTime until, Pageable pageable);

    // use the same time for creation and completion
    @Modifying
    @Query(value = """
        INSERT INTO public.wallet_operations
            (portfolio_id, reference, idempotency_key, operation_type, amount, currency,
             status, balance_after, created_at, completed_at)
        VALUES (:portfolioId, :reference, :idempotencyKey, 'WITHDRAWAL', :amount, 'USD',
                'COMPLETED', :balanceAfter, LOCALTIMESTAMP, LOCALTIMESTAMP)
        """, nativeQuery = true)
    int insertCompletedWithdrawal(@Param("portfolioId") Long portfolioId,
                                  @Param("reference") UUID reference,
                                  @Param("idempotencyKey") String idempotencyKey,
                                  @Param("amount") BigDecimal amount,
                                  @Param("balanceAfter") BigDecimal balanceAfter);

    //find a portfolio's wallet operation using its idempotency key
    Optional<WalletOperation> findByPortfolio_IdAndIdempotencyKey(Long portfolioId, String idempotencyKey);

    //find a wallet operation by reference belonging to the investor's portfolio
    Optional<WalletOperation> findByReferenceAndPortfolio_Id(UUID reference, Long portfolioId);

    //retrieve the portfolio's wallet history, newest first
    Page<WalletOperation> findByPortfolio_IdOrderByCreatedAtDescIdDesc(Long portfolioId, Pageable pageable);

    // find a wallet operation by provider invoice id
    Optional<WalletOperation> findByProviderInvoiceId(Long providerInvoiceId);

    //lock a wallet operation during payment verification
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WalletOperation> findWithLockById(Long id);

    //sum completed deposits for one portfolio; other statuses do not affect funding
    @Query("""
        SELECT COALESCE(SUM(w.amount), 0) FROM WalletOperation w
        WHERE w.portfolio.id = :portfolioId
        AND w.operationType = com.ga.investmentservice.Enums.WalletOperationType.DEPOSIT
        AND w.status = com.ga.investmentservice.Enums.WalletOperationStatus.COMPLETED
        """)
    BigDecimal sumCompletedDepositsByPortfolioId(@Param("portfolioId") Long portfolioId);

    //sum completed withdrawals for one portfolio
    @Query("""
        SELECT COALESCE(SUM(w.amount), 0) FROM WalletOperation w
        WHERE w.portfolio.id = :portfolioId
        AND w.operationType = com.ga.investmentservice.Enums.WalletOperationType.WITHDRAWAL
        AND w.status = com.ga.investmentservice.Enums.WalletOperationStatus.COMPLETED
        """)
    BigDecimal sumCompletedWithdrawalsByPortfolioId(@Param("portfolioId") Long portfolioId);
}
