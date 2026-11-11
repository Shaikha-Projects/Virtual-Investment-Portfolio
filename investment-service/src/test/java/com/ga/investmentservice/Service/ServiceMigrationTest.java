package com.ga.investmentservice.Service;

import com.ga.investmentservice.DTO.Request.*;
import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.DTO.Response.UserIdentityResponse;
import com.ga.investmentservice.Enums.*;
import com.ga.investmentservice.Event.*;
import com.ga.investmentservice.Exception.BusinessRuleException;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import com.ga.investmentservice.Model.*;
import com.ga.investmentservice.Repository.*;
import com.ga.investmentservice.Security.AuthenticatedUser;
import com.ga.investmentservice.Security.CurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceMigrationTest {
    private final AssetRepository assets = mock(AssetRepository.class);
    private final PortfolioRepository portfolios = mock(PortfolioRepository.class);
    private final HoldingRepository holdings = mock(HoldingRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final InvestmentOrderRepository orders = mock(InvestmentOrderRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final InvestmentEventService events = mock(InvestmentEventService.class);
    private final AuthServiceClient authServiceClient = mock(AuthServiceClient.class);
    private final CurrentUser currentUser = new CurrentUser();
    private final TradeExecutionService trades = new TradeExecutionService(holdings, transactions);
    private final Portfolio portfolio = new Portfolio();
    private final Asset asset = new Asset();
    private final Holding holding = new Holding();
    private final OrderExecutionService execution = new OrderExecutionService(orders, portfolios, holdings, trades, events, audit, assets, authServiceClient);

    @BeforeEach
    void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(42L, "owner@example.com", "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        portfolio.setId(5L);
        portfolio.setUserId(42L);
        portfolio.setCashBalance(new BigDecimal("1000.00"));
        portfolio.setReservedCash(new BigDecimal("100.00"));
        asset.setId(1L);
        asset.setSymbol("ABC");
        asset.setCurrentPrice(new BigDecimal("10.00"));
        asset.setAssetStatus(AssetStatus.ACTIVE);
        holding.setPortfolio(portfolio);
        holding.setAsset(asset);
        holding.setQuantity(new BigDecimal("2.00"));
        holding.setAverageBuyPrice(new BigDecimal("8.00"));
        holding.setReservedQuantity(new BigDecimal("0.50"));
        when(portfolios.findByUserId(42L)).thenReturn(Optional.of(portfolio));
        when(portfolios.findByIdForUpdate(5L)).thenReturn(Optional.of(portfolio));
        when(authServiceClient.getUserById(42L)).thenReturn(
                new UserIdentityResponse(42L, "owner@example.com", "USER", "ACTIVE"));
        when(assets.findById(1L)).thenReturn(Optional.of(asset));
        when(assets.findByIdForUpdate(1L)).thenReturn(Optional.of(asset));
        when(holdings.findByPortfolioAndAsset(portfolio, asset)).thenReturn(Optional.of(holding));
        when(transactions.save(any(Transaction.class))).thenAnswer(call -> {
            Transaction transaction = call.getArgument(0);
            transaction.setId(99L);
            return transaction;
        });
        ReflectionTestUtils.setField(execution, "entityManager", mock(EntityManager.class));
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    private AssetService assetService() {
        AssetService service = new AssetService(assets, currentUser, holdings, transactions,
                portfolios, audit, trades, events);
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
        return service;
    }

    private InvestmentOrderService orderService() {
        InvestmentOrderService service = new InvestmentOrderService(orders, currentUser, portfolios,
                assets, holdings, trades, execution, events, audit, mock(ThreadPoolTaskExecutor.class));
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
        return service;
    }

    private InvestmentOrder order(TransactionType type) {
        InvestmentOrder order = new InvestmentOrder();
        order.setId(7L);
        order.setPortfolio(portfolio);
        order.setAsset(asset);
        order.setOrderType(type);
        order.setQuantity(new BigDecimal("1.00"));
        order.setLimitPrice(new BigDecimal("10.00"));
        order.setStatus(OrderStatus.PENDING);
        when(orders.findByIdForUpdate(7L)).thenReturn(Optional.of(order));
        when(orders.findOrderOwnerId(7L)).thenReturn(Optional.of(42L));
        return order;
    }

    @Test
    void buyAndSellPreserveBalancesCostBasisAndNotificationContent() {
        AssetService service = assetService();
        BuyAssetRequest buy = mock(BuyAssetRequest.class);
        when(buy.getAssetId()).thenReturn(1L);
        when(buy.getQuantity()).thenReturn(new BigDecimal("1.50"));
        assertEquals(new BigDecimal("985.00"), service.buyAsset("owner@example.com", buy).getCashBalance());
        assertEquals(new BigDecimal("8.86"), holding.getAverageBuyPrice());
        assertEquals(new BigDecimal("100.00"), portfolio.getReservedCash());
        verify(events).saveInvestmentNotificationEvent(new InvestmentNotificationEvent(
                "owner@example.com", "Purchase completed: 1.50 shares of ABC"));
        verify(events).saveTransactionEvent(new TransactionCompletedEvent(
                "owner@example.com", "BUY", "ABC", new BigDecimal("1.50"), new BigDecimal("15.00")));

        SellAssetRequest sell = mock(SellAssetRequest.class);
        when(sell.getAssetId()).thenReturn(1L);
        when(sell.getQuantity()).thenReturn(new BigDecimal("1.00"));
        assertEquals(new BigDecimal("995.00"), service.sellAsset("owner@example.com", sell).getCashBalance());
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactions, times(2)).save(saved.capture());
        assertEquals(new BigDecimal("8.86"), saved.getValue().getCostBasis());
        assertEquals(new BigDecimal("1.14"), saved.getValue().getRealizedGainLoss());
        assertEquals(new BigDecimal("0.50"), holding.getReservedQuantity());
        verify(events).saveInvestmentNotificationEvent(new InvestmentNotificationEvent(
                "owner@example.com", "Sale completed: 1.00 shares of ABC"));
    }

    @Test
    void identityMismatchIsRejectedBeforeRepositoryAccess() {
        assertThrows(AccessDeniedException.class,
                () -> assetService().getHoldings("victim@example.com"));
        verify(portfolios, never()).findByUserId(anyLong());
        verifyNoInteractions(events);
    }

    @Test
    void buyOrderReservationsAreReleasedOnCancellation() {
        InvestmentOrderService service = orderService();
        CreateOrderRequest request = mock(CreateOrderRequest.class);
        when(request.getAssetId()).thenReturn(1L);
        when(request.getOrderType()).thenReturn(TransactionType.BUY);
        when(request.getQuantity()).thenReturn(new BigDecimal("3.00"));
        when(request.getLimitPrice()).thenReturn(new BigDecimal("9.00"));
        service.placeLimitOrder("owner@example.com", request);
        assertEquals(new BigDecimal("127.00"), portfolio.getReservedCash());
        InvestmentOrder order = order(TransactionType.BUY);
        order.setQuantity(new BigDecimal("3.00"));
        order.setLimitPrice(new BigDecimal("9.00"));
        service.cancelOrder("owner@example.com", 7L);
        assertEquals(new BigDecimal("100.00"), portfolio.getReservedCash());
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(events).saveInvestmentNotificationEvent(new InvestmentNotificationEvent(
                "owner@example.com", "Limit order cancelled: 3.00 shares of ABC"));
    }

    @Test
    void sellOrderReservationsAreReleasedOnCancellation() {
        InvestmentOrderService service = orderService();
        CreateOrderRequest request = mock(CreateOrderRequest.class);
        when(request.getAssetId()).thenReturn(1L);
        when(request.getOrderType()).thenReturn(TransactionType.SELL);
        when(request.getQuantity()).thenReturn(new BigDecimal("1.00"));
        when(request.getLimitPrice()).thenReturn(new BigDecimal("11.00"));
        service.placeLimitOrder("owner@example.com", request);
        assertEquals(new BigDecimal("1.50"), holding.getReservedQuantity());
        service.cancelOrder("owner@example.com", order(TransactionType.SELL).getId());
        assertEquals(new BigDecimal("0.50"), holding.getReservedQuantity());
    }

    @Test
    void cannotCancelAnotherInvestorsOrder() {
        InvestmentOrder order = order(TransactionType.BUY);
        Portfolio other = new Portfolio();
        other.setUserId(999L);
        order.setPortfolio(other);
        assertThrows(BusinessRuleException.class, () -> orderService().cancelOrder("owner@example.com", 7L));
        verify(portfolios, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(events, audit);
    }

    @Test
    void backgroundBuyUsesStoredOwnerAndDoesNotRequireHttpIdentity() {
        InvestmentOrder order = order(TransactionType.BUY);
        SecurityContextHolder.clearContext();
        execution.executeSingleOrder(7L);
        assertEquals(new BigDecimal("990.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("90.00"), portfolio.getReservedCash());
        assertEquals(OrderStatus.FILLED, order.getStatus());
        verify(audit).logOrderExecution(order, "owner@example.com", "Executed BUY limit order #7 for 1.00 shares of ABC");
        verify(events).saveTransactionEvent(new TransactionCompletedEvent(
                "owner@example.com", "BUY", "ABC", new BigDecimal("1.00"), new BigDecimal("10.00")));
        verify(events).saveInvestmentNotificationEvent(new InvestmentNotificationEvent(
                "owner@example.com", "Limit BUY order executed: 1.00 shares of ABC"));
        execution.executeSingleOrder(7L);
        verify(events, times(1)).saveTransactionEvent(any());
    }

    @Test
    void backgroundSellPreservesCostBasisAndReservationRelease() {
        InvestmentOrder order = order(TransactionType.SELL);
        holding.setReservedQuantity(new BigDecimal("1.50"));
        SecurityContextHolder.clearContext();
        execution.executeSingleOrder(7L);
        assertEquals(new BigDecimal("1010.00"), portfolio.getCashBalance());
        assertEquals(new BigDecimal("0.50"), holding.getReservedQuantity());
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactions).save(saved.capture());
        assertEquals(new BigDecimal("8.00"), saved.getValue().getCostBasis());
        assertEquals(new BigDecimal("2.00"), saved.getValue().getRealizedGainLoss());
        verify(audit).logOrderExecution(order, "owner@example.com", "Executed SELL limit order #7 for 1.00 shares of ABC");
    }

    @Test
    void watchlistUsesVerifiedIdForCreationAndDeletion() {
        WatchlistRepository repository = mock(WatchlistRepository.class);
        WatchlistService service = new WatchlistService(currentUser, assets, repository);
        service.addToWatchlist("owner@example.com", 1L);
        ArgumentCaptor<Watchlist> saved = ArgumentCaptor.forClass(Watchlist.class);
        verify(repository).save(saved.capture());
        assertEquals(42L, saved.getValue().getUserId());
        assertThrows(InformationNotFoundException.class,
                () -> service.removeFromWatchlist("owner@example.com", 123L));
        verify(repository).findByIdAndUserId(123L, 42L);
        verify(repository, never()).delete(any());
    }

    @Test
    void backgroundPriceAlertsKeepOriginalMessageAndSkipAlreadyTriggeredAlerts() {
        PriceAlertRepository repository = mock(PriceAlertRepository.class);
        PriceAlertService service = new PriceAlertService(repository, portfolios, assets, currentUser, events, authServiceClient);
        PriceAlert alert = new PriceAlert();
        alert.setId(8L);
        alert.setPortfolio(portfolio);
        alert.setCondition(PriceAlertCondition.ABOVE);
        alert.setTargetPrice(new BigDecimal("11.00"));
        alert.setStatus(PriceAlertStatus.ACTIVE);
        when(repository.findByAssetAndStatusOrderByIdAsc(asset, PriceAlertStatus.ACTIVE)).thenReturn(List.of(alert));
        when(repository.findByIdForUpdate(8L)).thenReturn(Optional.of(alert));
        SecurityContextHolder.clearContext();
        service.checkAlertsForAsset(asset, new BigDecimal("11.50"));
        assertEquals(PriceAlertStatus.TRIGGERED, alert.getStatus());
        verify(events).savePriceAlertEvent(new PriceAlertTriggeredEvent(
                "owner@example.com", "Price alert triggered: ABC reached $11.50 (target: $11.00)"));
        service.checkAlertsForAsset(asset, new BigDecimal("11.50"));
        verify(events, times(1)).savePriceAlertEvent(any());
    }

    @Test
    void snapshotsPreserveRoundedPortfolioValueAndHistoryOwnership() {
        PortfolioSnapshotRepository repository = mock(PortfolioSnapshotRepository.class);
        PortfolioSnapshotService service = new PortfolioSnapshotService(portfolios, holdings, repository, currentUser);
        when(holdings.findByPortfolio(portfolio)).thenReturn(List.of(holding));
        service.saveSnapshot(portfolio);
        ArgumentCaptor<PortfolioSnapshot> saved = ArgumentCaptor.forClass(PortfolioSnapshot.class);
        verify(repository).save(saved.capture());
        assertEquals(new BigDecimal("1020.00"), saved.getValue().getTotalPortfolioValue());
        service.getPortfolioHistory("owner@example.com");
        verify(repository).findByPortfolioOrderByRecordedAtAsc(portfolio);
    }

    @Test
    void marketSimulationStaysOffUntilExplicitlyEnabled() {
        MarketSimulationService service = new MarketSimulationService(assets, mock(PriceHistoryRepository.class),
                mock(MarketPriceUpdateService.class), mock(ThreadPoolTaskExecutor.class));
        service.updateMarketPrices();
        verify(assets, never()).findByAssetStatus(any());
    }
}
