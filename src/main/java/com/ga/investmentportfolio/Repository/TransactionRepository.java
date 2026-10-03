package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    //all transactions
    List<Transaction> findByPortfolioOrderByCreatedAtDesc(Portfolio portfolio);

    //filter by transaction type (buy/sell)
    List<Transaction> findByPortfolioAndTransactionTypeOrderByCreatedAtDesc(Portfolio portfolio, TransactionType transactionType);

    //filter by asset symbol
    List<Transaction> findByPortfolioAndAssetSymbolOrderByCreatedAtDesc(Portfolio portfolio, String symbol);

    //filter by transaction type (buy/sell) and asset symbol
    List<Transaction> findByPortfolioAndTransactionTypeAndAssetSymbolOrderByCreatedAtDesc(Portfolio portfolio, TransactionType transactionType, String symbol);



}
