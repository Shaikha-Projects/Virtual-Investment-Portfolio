package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

}
