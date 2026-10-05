package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.BuyAssetRequest;
import com.ga.investmentportfolio.DTO.Request.SellAssetRequest;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Exception.BusinessRuleException;
import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.Holding;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.Mockito.mock;


public class AssetServiceTest {
    //dependencies
    private AssetRepository assetRepository;
    private UserRepository userRepository;
    private HoldingRepository holdingRepository;
    private TransactionRepository transactionRepository;
    private PortfolioRepository portfolioRepository;
    private AuditLogService auditLogService;
    private NotificationService notificationService;

    //asset service
    private AssetService assetService;

    @BeforeEach
    public void setUp() {
        assetRepository = mock(AssetRepository.class);
        userRepository = mock(UserRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        portfolioRepository = mock(PortfolioRepository.class);
        auditLogService = mock(AuditLogService.class);
        notificationService = mock(NotificationService.class);

        assetService = new AssetService(
                assetRepository, userRepository, holdingRepository,
                transactionRepository, portfolioRepository, auditLogService, notificationService);
    }

    //test 1: insufficient cash → BUY rejected
    @Test
    @DisplayName("When user has insufficient cash then buy is rejected")
    public void whenUserHasInsufficientCashThenBuyIsRejected() {
        //create user
        String email = "user@test.com";
        User user = new User();
        user.setEmailAddress(email);

        //give user portfolio with only 100
        Portfolio portfolio = new Portfolio();
        portfolio.setCashBalance(new BigDecimal("100.00"));
        user.setPortfolio(portfolio);

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

        when(userRepository.findByEmailAddress(email)).thenReturn(Optional.of(user));

        when(portfolioRepository.findById(user.getId())).thenReturn(Optional.of(portfolio));

        when(assetRepository.findById(1L)).thenReturn(Optional.of(asset));

        assertThrows(BusinessRuleException.class, () -> assetService.buyAsset(email, request));
    }

    //test 2: sell more than owned → SELL rejected
    @Test
    @DisplayName("When user sells more shares than owned then sell is rejected")
    public void whenUserSellsMoreSharesThanOwnedThenSellIsRejected() {
        //create user
        String email = "user@test.com";
        User user = new User();
        user.setEmailAddress(email);

        //give user portfolio with only 100
        Portfolio portfolio = new Portfolio();
        portfolio.setCashBalance(new BigDecimal("100.00"));
        user.setPortfolio(portfolio);

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
        when(userRepository.findByEmailAddress(email)).thenReturn(Optional.of(user));

        when(assetRepository.findById(1L)).thenReturn(Optional.of(asset));

        when(holdingRepository.findByPortfolioAndAsset(portfolio, asset)).thenReturn(Optional.of(holding));

        //verify sale is rejected
        assertThrows(BusinessRuleException.class, () -> assetService.sellAsset(email, request));
    }

    //test 3: inactive user cannot log in



    }
