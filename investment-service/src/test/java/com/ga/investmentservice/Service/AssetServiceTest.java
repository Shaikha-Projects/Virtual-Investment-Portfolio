package com.ga.investmentservice.Service;

import com.ga.investmentservice.DTO.Request.BuyAssetRequest;
import com.ga.investmentservice.DTO.Request.SellAssetRequest;
import com.ga.investmentservice.DTO.Response.PortfolioPerformanceResponse;
import com.ga.investmentservice.Enums.AssetStatus;
import com.ga.investmentservice.Enums.TransactionStatus;
import com.ga.investmentservice.Enums.TransactionType;
import com.ga.investmentservice.Enums.WalletOperationStatus;
import com.ga.investmentservice.Enums.WalletOperationType;
import com.ga.investmentservice.Exception.BusinessRuleException;
import com.ga.investmentservice.Model.Asset;
import com.ga.investmentservice.Model.Holding;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.WalletOperation;
import com.ga.investmentservice.Security.CurrentUser;
import com.ga.investmentservice.Repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.*;


public class AssetServiceTest {
    //dependencies
    private AssetRepository assetRepository;
    private CurrentUser currentUser;
    private HoldingRepository holdingRepository;
    private TransactionRepository transactionRepository;
    private PortfolioRepository portfolioRepository;
    private AuditLogService auditLogService;
    private WalletOperationRepository walletOperationRepository;

    //asset service
    private AssetService assetService;

    @BeforeEach
    public void setUp() {
        assetRepository = mock(AssetRepository.class);
        currentUser = mock(CurrentUser.class);
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        portfolioRepository = mock(PortfolioRepository.class);
        auditLogService = mock(AuditLogService.class);
        walletOperationRepository = mock(WalletOperationRepository.class);

        assetService = new AssetService(
                assetRepository, currentUser, holdingRepository,
                transactionRepository, portfolioRepository, auditLogService,
                new TradeExecutionService(holdingRepository, transactionRepository), mock(InvestmentEventService.class),
                walletOperationRepository);
        ReflectionTestUtils.setField(assetService, "entityManager", mock(EntityManager.class));
    }

    //test 1: insufficient cash → BUY rejected
    @Test
    @DisplayName("When user has insufficient cash then buy is rejected")
    public void whenUserHasInsufficientCashThenBuyIsRejected() {
        //create user
        String email = "user@test.com";


        //give user portfolio with only 100
        Portfolio portfolio = new Portfolio();
        portfolio.setCashBalance(new BigDecimal("100.00"));
        portfolio.setId(5L);
        portfolio.setUserId(42L);

        //create active asset costing $200
        Asset asset = new Asset();
        asset.setId(1L);
        asset.setSymbol("AAPL");
        asset.setCurrentPrice(new BigDecimal("200.00"));
        asset.setAssetStatus(AssetStatus.ACTIVE);

        //create buy asset request
        BuyAssetRequest request = mock(BuyAssetRequest.class);
        when(request.getAssetId()).thenReturn(1L);
        when(request.getQuantity()).thenReturn(new BigDecimal("1"));

        when(currentUser.getUserId(email)).thenReturn(42L);
        when(portfolioRepository.findByUserId(42L)).thenReturn(Optional.of(portfolio));

        when(portfolioRepository.findByIdForUpdate(portfolio.getId())).thenReturn(Optional.of(portfolio));

        when(assetRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(asset));

        assertThrows(BusinessRuleException.class, () -> assetService.buyAsset(email, request));
    }

    //test 2: sell more than owned → SELL rejected
    @Test
    @DisplayName("When user sells more shares than owned then sell is rejected")
    public void whenUserSellsMoreSharesThanOwnedThenSellIsRejected() {
        //create user
        String email = "user@test.com";


        //give user portfolio with only 100
        Portfolio portfolio = new Portfolio();
        portfolio.setCashBalance(new BigDecimal("100.00"));
        portfolio.setId(5L);
        portfolio.setUserId(42L);

        //create active asset costing $200
        Asset asset = new Asset();
        asset.setId(1L);
        asset.setSymbol("AAPL");
        asset.setCurrentPrice(new BigDecimal("200.00"));
        asset.setAssetStatus(AssetStatus.ACTIVE);

        //user owns only 2 shares
        Holding holding = new Holding();
        holding.setPortfolio(portfolio);
        holding.setAsset(asset);
        holding.setQuantity(new BigDecimal("2"));

        //user tries to sell 5 shares
        SellAssetRequest request = mock(SellAssetRequest.class);
        when(request.getAssetId()).thenReturn(1L);
        when(request.getQuantity()).thenReturn(new BigDecimal("5"));

        //mock repository responses
        when(currentUser.getUserId(email)).thenReturn(42L);
        when(portfolioRepository.findByUserId(42L)).thenReturn(Optional.of(portfolio));
        when(portfolioRepository.findByIdForUpdate(portfolio.getId())).thenReturn(Optional.of(portfolio));

        when(assetRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(asset));

        when(holdingRepository.findByPortfolioAndAsset(portfolio, asset)).thenReturn(Optional.of(holding));

        //verify sale is rejected
        assertThrows(BusinessRuleException.class, () -> assetService.sellAsset(email, request));
    }

    @Test
    public void legacyPortfolioWithoutWalletFundingPreservesPerformance() {
        performancePortfolio("100000.00", "100000.00", List.of(), "0", "0", null);

        PortfolioPerformanceResponse response = assetService.getPortfolioPerformance("user@test.com");

        assertEquals(new BigDecimal("100000.00"), response.getTotalPortfolioValue());
        assertEquals(new BigDecimal("0.00"), response.getTotalGainLoss());
        assertEquals(new BigDecimal("0.0000"), response.getTotalReturnPercentage());
        assertEquals(BigDecimal.ZERO, response.getRealizedGainLoss());
    }

    @Test
    public void completedDepositDoesNotAppearAsInvestmentProfit() {
        performancePortfolio("100000.00", "100050.00", List.of(), "50.00", "0", null);

        PortfolioPerformanceResponse response = assetService.getPortfolioPerformance("user@test.com");

        assertEquals(new BigDecimal("100050.00"), response.getCashBalance());
        assertEquals(new BigDecimal("0.00"), response.getTotalGainLoss());
        assertEquals(new BigDecimal("0.0000"), response.getTotalReturnPercentage());
        verify(walletOperationRepository).sumCompletedDepositsByPortfolioId(5L);
        verify(walletOperationRepository).sumCompletedWithdrawalsByPortfolioId(5L);
    }

    @Test
    public void completedWithdrawalDoesNotAppearAsInvestmentLoss() {
        performancePortfolio("100000.00", "99950.00", List.of(), "0", "50.00", null);

        PortfolioPerformanceResponse response = assetService.getPortfolioPerformance("user@test.com");

        assertEquals(new BigDecimal("0.00"), response.getTotalGainLoss());
        assertEquals(new BigDecimal("0.0000"), response.getTotalReturnPercentage());
    }

    @Test
    public void depositsAndWithdrawalsPreserveProfitAndUseContributedCapitalForPercentage() {
        performancePortfolio("100000.00", "100230.00", List.of(), "50.00", "20.00", "200.00");

        PortfolioPerformanceResponse response = assetService.getPortfolioPerformance("user@test.com");

        assertEquals(new BigDecimal("200.00"), response.getTotalGainLoss());
        assertEquals(new BigDecimal("0.2000"), response.getTotalReturnPercentage());
    }

    @Test
    public void zeroInitialFundingUsesCompletedDepositsAsCapital() {
        performancePortfolio("0.00", "80.00", List.of(), "100.00", "40.00", "20.00");

        PortfolioPerformanceResponse response = assetService.getPortfolioPerformance("user@test.com");

        assertEquals(new BigDecimal("20.00"), response.getTotalGainLoss());
        assertEquals(new BigDecimal("20.0000"), response.getTotalReturnPercentage());
    }

    @Test
    public void zeroContributedCapitalReturnsZeroPercentageWithoutDivision() {
        performancePortfolio("0.00", "0.00", List.of(), "0", "0", null);

        PortfolioPerformanceResponse response = assetService.getPortfolioPerformance("user@test.com");

        assertEquals(0, response.getTotalGainLoss().compareTo(BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, response.getTotalReturnPercentage());
    }

    @Test
    public void existingHoldingCostBasisAndTradingGainsKeepTheirCalculationsAndRounding() {
        Asset asset = new Asset();
        asset.setCurrentPrice(new BigDecimal("120.00"));
        Holding holding = new Holding();
        holding.setAsset(asset);
        holding.setQuantity(new BigDecimal("10"));
        holding.setAverageBuyPrice(new BigDecimal("100.00"));
        Portfolio portfolio = performancePortfolio("100000.00", "99125.40", List.of(holding), "0", "0", "125.40");
        portfolio.setReservedCash(new BigDecimal("100.00"));

        PortfolioPerformanceResponse response = assetService.getPortfolioPerformance("user@test.com");

        assertEquals(new BigDecimal("1200.00"), response.getHoldingsValue());
        assertEquals(new BigDecimal("1000.00"), response.getTotalCostBasis());
        assertEquals(new BigDecimal("200.00"), response.getUnrealizedGainLoss());
        assertEquals(new BigDecimal("125.40"), response.getRealizedGainLoss());
        assertEquals(new BigDecimal("100325.40"), response.getTotalPortfolioValue());
        assertEquals(new BigDecimal("325.40"), response.getTotalGainLoss());
        // keep the existing rounding before multiplying by 100
        assertEquals(new BigDecimal("0.3300"), response.getTotalReturnPercentage());
        assertEquals(new BigDecimal("99125.40"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("100.00"), portfolio.getReservedCash());
        verify(portfolioRepository, never()).save(any());
        verify(holdingRepository, never()).save(any());
    }

    @Test
    public void onlyCompletedFundingForTheCurrentPortfolioAffectsPerformance() {
        Portfolio portfolio = performancePortfolio("100000.00", "100030.00", List.of(), "0", "0", null);
        List<WalletOperation> operations = new ArrayList<>();
        operations.add(funding(portfolio, WalletOperationType.DEPOSIT, WalletOperationStatus.COMPLETED, "50.00"));
        operations.add(funding(portfolio, WalletOperationType.WITHDRAWAL, WalletOperationStatus.COMPLETED, "20.00"));
        for (WalletOperationStatus status : List.of(WalletOperationStatus.PENDING,
                WalletOperationStatus.FAILED, WalletOperationStatus.CANCELLED)) {
            operations.add(funding(portfolio, WalletOperationType.DEPOSIT, status, "999.00"));
            operations.add(funding(portfolio, WalletOperationType.WITHDRAWAL, status, "777.00"));
        }
        Portfolio other = new Portfolio();
        other.setId(99L);
        operations.add(funding(other, WalletOperationType.DEPOSIT, WalletOperationStatus.COMPLETED, "10000.00"));
        // repository mocks do not verify real database queries
        when(walletOperationRepository.sumCompletedDepositsByPortfolioId(anyLong())).thenAnswer(call ->
                completedFunding(operations, call.getArgument(0), WalletOperationType.DEPOSIT));
        when(walletOperationRepository.sumCompletedWithdrawalsByPortfolioId(anyLong())).thenAnswer(call ->
                completedFunding(operations, call.getArgument(0), WalletOperationType.WITHDRAWAL));

        PortfolioPerformanceResponse response = assetService.getPortfolioPerformance("user@test.com");

        assertEquals(new BigDecimal("0.00"), response.getTotalGainLoss());
        assertEquals(new BigDecimal("0.0000"), response.getTotalReturnPercentage());
        verify(walletOperationRepository).sumCompletedDepositsByPortfolioId(5L);
        verify(walletOperationRepository).sumCompletedWithdrawalsByPortfolioId(5L);
    }

    @Test
    public void performanceStartsItsOwnReadOnlyRepeatableReadTransactionInsideReadCommittedCaller() throws Exception {
        Portfolio portfolio = performancePortfolio("100000.00", "100050.00", List.of(), "50.00", "0", null);
        DataSource dataSource = mock(DataSource.class);
        Connection outerConnection = mock(Connection.class);
        Connection snapshotConnection = mock(Connection.class);
        for (Connection connection : List.of(outerConnection, snapshotConnection)) {
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        }
        when(dataSource.getConnection()).thenReturn(outerConnection, snapshotConnection);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        ProxyFactory factory = new ProxyFactory(assetService);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        AssetService transactionalService = (AssetService) factory.getProxy();

        when(portfolioRepository.findByUserId(42L)).thenAnswer(call -> {
            assertPerformanceSnapshot();
            return Optional.of(portfolio);
        });
        when(holdingRepository.findByPortfolio(portfolio)).thenAnswer(call -> {
            assertPerformanceSnapshot();
            return List.of();
        });
        when(walletOperationRepository.sumCompletedDepositsByPortfolioId(5L)).thenAnswer(call -> {
            assertPerformanceSnapshot();
            return new BigDecimal("50.00");
        });
        when(walletOperationRepository.sumCompletedWithdrawalsByPortfolioId(5L)).thenAnswer(call -> {
            assertPerformanceSnapshot();
            return BigDecimal.ZERO;
        });
        when(transactionRepository.sumRealizedGainLoss(portfolio, TransactionType.SELL, TransactionStatus.COMPLETED))
                .thenAnswer(call -> { assertPerformanceSnapshot(); return BigDecimal.ZERO; });

        TransactionTemplate outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.execute(status -> {
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            assertEquals(new BigDecimal("0.00"), transactionalService.getPortfolioPerformance("user@test.com").getTotalGainLoss());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return null;
        });
        verify(snapshotConnection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(snapshotConnection).setReadOnly(true);
        verify(snapshotConnection).commit();
        verify(outerConnection).commit();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private void assertPerformanceSnapshot() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
    }

    private Portfolio performancePortfolio(String initialFunding, String cash, List<Holding> holdings,
                                            String deposits, String withdrawals, String realizedGainLoss) {
        Portfolio portfolio = new Portfolio();
        portfolio.setId(5L);
        portfolio.setUserId(42L);
        portfolio.setInitialFunding(new BigDecimal(initialFunding));
        portfolio.setCashBalance(new BigDecimal(cash));
        when(currentUser.getUserId("user@test.com")).thenReturn(42L);
        when(portfolioRepository.findByUserId(42L)).thenReturn(Optional.of(portfolio));
        when(holdingRepository.findByPortfolio(portfolio)).thenReturn(holdings);
        when(walletOperationRepository.sumCompletedDepositsByPortfolioId(5L)).thenReturn(new BigDecimal(deposits));
        when(walletOperationRepository.sumCompletedWithdrawalsByPortfolioId(5L)).thenReturn(new BigDecimal(withdrawals));
        when(transactionRepository.sumRealizedGainLoss(portfolio, TransactionType.SELL, TransactionStatus.COMPLETED))
                .thenReturn(realizedGainLoss == null ? null : new BigDecimal(realizedGainLoss));
        return portfolio;
    }

    private WalletOperation funding(Portfolio portfolio, WalletOperationType type, WalletOperationStatus status, String amount) {
        WalletOperation operation = new WalletOperation();
        operation.setPortfolio(portfolio);
        operation.setOperationType(type);
        operation.setStatus(status);
        operation.setAmount(new BigDecimal(amount));
        return operation;
    }

    private BigDecimal completedFunding(List<WalletOperation> operations, Long portfolioId, WalletOperationType type) {
        return operations.stream().filter(operation -> operation.getPortfolio().getId().equals(portfolioId)
                        && operation.getOperationType() == type && operation.getStatus() == WalletOperationStatus.COMPLETED)
                .map(WalletOperation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }



    }
