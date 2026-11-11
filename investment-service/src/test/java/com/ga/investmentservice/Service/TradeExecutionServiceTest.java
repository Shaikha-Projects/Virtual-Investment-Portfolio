package com.ga.investmentservice.Service;

import com.ga.investmentservice.Enums.TransactionStatus;
import com.ga.investmentservice.Enums.TransactionType;
import com.ga.investmentservice.Exception.BusinessRuleException;
import com.ga.investmentservice.Model.Asset;
import com.ga.investmentservice.Model.Holding;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.Transaction;
import com.ga.investmentservice.Repository.HoldingRepository;
import com.ga.investmentservice.Repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeExecutionServiceTest {
    private final HoldingRepository holdingRepository = mock(HoldingRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final Portfolio portfolio = new Portfolio();
    private final Asset asset = new Asset();
    private final TradeExecutionService service = new TradeExecutionService(holdingRepository, transactionRepository);
    private Holding holding;

    @BeforeEach
    void setup() {
        holding = new Holding();
        holding.setQuantity(new BigDecimal("10"));
        holding.setAverageBuyPrice(new BigDecimal("20.00"));
        holding.setReservedQuantity(new BigDecimal("4"));
        when(holdingRepository.findByPortfolioAndAsset(portfolio, asset)).thenReturn(Optional.of(holding));
    }

    @Test
    void buyPreservesWeightedAverageAndReservedShares() {
        service.updateHoldingAfterBuy(portfolio, asset, new BigDecimal("3"), new BigDecimal("25.00"));
        assertEquals(new BigDecimal("13"), holding.getQuantity());
        assertEquals(new BigDecimal("21.15"), holding.getAverageBuyPrice());
        assertEquals(new BigDecimal("4"), holding.getReservedQuantity());
        verify(holdingRepository).save(holding);
    }

    @Test
    void firstBuyCreatesHoldingWithoutReservations() {
        when(holdingRepository.findByPortfolioAndAsset(portfolio, asset)).thenReturn(Optional.empty());
        service.updateHoldingAfterBuy(portfolio, asset, new BigDecimal("2"), new BigDecimal("12.34"));
        ArgumentCaptor<Holding> saved = ArgumentCaptor.forClass(Holding.class);
        verify(holdingRepository).save(saved.capture());
        assertSame(portfolio, saved.getValue().getPortfolio());
        assertSame(asset, saved.getValue().getAsset());
        assertEquals(new BigDecimal("2"), saved.getValue().getQuantity());
        assertEquals(new BigDecimal("12.34"), saved.getValue().getAverageBuyPrice());
        assertEquals(BigDecimal.ZERO, saved.getValue().getReservedQuantity());
    }

    @Test
    void sellRejectsOversellingAndSellingReservedSharesWithoutMutation() {
        assertThrows(BusinessRuleException.class,
                () -> service.updateHoldingAfterSell(portfolio, asset, new BigDecimal("11")));
        assertThrows(BusinessRuleException.class,
                () -> service.updateHoldingAfterSell(portfolio, asset, new BigDecimal("7")));
        assertEquals(new BigDecimal("10"), holding.getQuantity());
        verify(holdingRepository, never()).save(any());
        verify(holdingRepository, never()).delete(any());
    }

    @Test
    void sellPreservesReservedSharesAndDeletesOnlyEmptyHolding() {
        service.updateHoldingAfterSell(portfolio, asset, new BigDecimal("6"));
        assertEquals(new BigDecimal("4"), holding.getQuantity());
        assertEquals(new BigDecimal("20.00"), holding.getAverageBuyPrice());
        verify(holdingRepository).save(holding);
        holding.setReservedQuantity(BigDecimal.ZERO);
        service.updateHoldingAfterSell(portfolio, asset, new BigDecimal("4"));
        verify(holdingRepository).delete(holding);
    }

    @Test
    void transactionsPreserveRoundingCostBasisAndRealizedLoss() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(call -> call.getArgument(0));
        Transaction sell = service.createTransaction(portfolio, asset, TransactionType.SELL,
                new BigDecimal("3"), new BigDecimal("10.125"), new BigDecimal("40.00"));
        assertEquals(new BigDecimal("30.38"), sell.getTotalAmount());
        assertEquals(new BigDecimal("40.00"), sell.getCostBasis());
        assertEquals(new BigDecimal("-9.62"), sell.getRealizedGainLoss());
        assertEquals(TransactionStatus.COMPLETED, sell.getTransactionStatus());
        assertSame(portfolio, sell.getPortfolio());
        assertSame(asset, sell.getAsset());
        Transaction buy = service.createTransaction(portfolio, asset, TransactionType.BUY,
                new BigDecimal("3"), new BigDecimal("10.125"), null);
        assertEquals(new BigDecimal("30.38"), buy.getTotalAmount());
        assertNull(buy.getCostBasis());
        assertNull(buy.getRealizedGainLoss());
    }
}
