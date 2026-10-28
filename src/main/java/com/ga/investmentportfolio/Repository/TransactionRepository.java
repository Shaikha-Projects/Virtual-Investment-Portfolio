package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Enums.TransactionStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    //all transactions
    Page<Transaction> findByPortfolio(Portfolio portfolio, Pageable pageable);

    //filter by transaction type (buy/sell)
    Page<Transaction> findByPortfolioAndTransactionType(Portfolio portfolio, TransactionType transactionType, Pageable pageable);

    //filter by asset symbol
    Page<Transaction> findByPortfolioAndAssetSymbol(Portfolio portfolio, String symbol, Pageable pageable);

    //filter by transaction type (buy/sell) and asset symbol
    Page<Transaction> findByPortfolioAndTransactionTypeAndAssetSymbol(Portfolio portfolio, TransactionType transactionType, String symbol, Pageable pageable);

    //calculate total realized profit/loss for completed SELL transactions
    @Query("""
    SELECT SUM(t.realizedGainLoss) FROM Transaction t
    WHERE t.portfolio = :portfolio
    AND t.transactionType = :type
    AND t.transactionStatus = :status
    """)
    BigDecimal sumRealizedGainLoss(@Param("portfolio") Portfolio portfolio, @Param("type") TransactionType type, @Param("status") TransactionStatus status);

}
