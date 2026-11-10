package com.ga.investmentservice.Service;

import com.ga.investmentservice.Enums.AuditAction;
import com.ga.investmentservice.Enums.OrderStatus;
import com.ga.investmentservice.Enums.TransactionType;
import com.ga.investmentservice.Event.InvestmentNotificationEvent;
import com.ga.investmentservice.Event.TransactionCompletedEvent;
import com.ga.investmentservice.Exception.BusinessRuleException;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import com.ga.investmentservice.Model.Asset;
import com.ga.investmentservice.Model.Holding;
import com.ga.investmentservice.Model.InvestmentOrder;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Repository.*;
import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.DTO.Response.UserIdentityResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExecutionService {
    private final InvestmentOrderRepository investmentOrderRepository;
    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final TradeExecutionService tradeExecutionService;
    private final InvestmentEventService investmentEventService;
    private final AuditLogService auditLogService;
    private final AssetRepository assetRepository;
    private final AuthServiceClient authServiceClient;

    @PersistenceContext
    private EntityManager entityManager;

    //method to execute each order in its transaction
    @Transactional
    public void executeSingleOrder(Long orderId) {
        // add log for testing
        log.info("Starting order execution - orderId: {}, thread: {}", orderId, Thread.currentThread().getName());

        //find the order owner before locking
        Long ownerId = investmentOrderRepository.findOrderOwnerId(orderId)
                .orElseThrow(() -> new InformationNotFoundException("Order not found"));

        //get user information from Authentication using REST Client
        UserIdentityResponse user = authServiceClient.getUserById(ownerId);

        if (!ownerId.equals(user.userId())) {
            throw new IllegalStateException("Order owner does not match Authentication");
        }

        String email = user.emailAddress();

        //retrieve order
        InvestmentOrder lockedOrder = investmentOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new InformationNotFoundException("Order not found"));

        //check order status after locking
        if (lockedOrder.getStatus() != OrderStatus.PENDING) {
            return;
        }

        //verify the order still belongs to the resolved user
        if (!lockedOrder.getPortfolio().getUserId().equals(ownerId)) {
            throw new IllegalStateException("Order owner changed");
        }

        //get asset current price and order's limit price
        BigDecimal currentPrice = lockedOrder.getAsset().getCurrentPrice();
        BigDecimal limitPrice = lockedOrder.getLimitPrice();

        //BUY
        if(lockedOrder.getOrderType() == TransactionType.BUY && currentPrice.compareTo(limitPrice) <= 0){
            //execute BUY limit orders
            //get user portfolio using locking method (portfolio locking)
            Portfolio portfolio = portfolioRepository.findByIdForUpdate(lockedOrder.getPortfolio().getId())
                    .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));

            //refresh portfolio to get the latest database values
            entityManager.refresh(portfolio);

            //lock asset to get latest price
            Asset asset = assetRepository.findByIdForUpdate(lockedOrder.getAsset().getId())
                    .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

            //get the latest price after locking the asset
            BigDecimal latestPrice = asset.getCurrentPrice();
            //check the limit price again
            if (latestPrice.compareTo(limitPrice) > 0) {
                return;
            }

            BigDecimal actualCost = lockedOrder.getQuantity().multiply(latestPrice).setScale(2, RoundingMode.HALF_UP);
            BigDecimal reservedCash = lockedOrder.getQuantity().multiply(limitPrice).setScale(2, RoundingMode.HALF_UP);

            //check sufficient reserved cash before execution
            if (portfolio.getReservedCash().compareTo(reservedCash) < 0) {
                throw new BusinessRuleException("Insufficient reserved cash");
            }

            //check sufficient cash balance before execution
            if (portfolio.getCashBalance().compareTo(actualCost) < 0) {
                throw new BusinessRuleException("Insufficient cash balance");
            }

            //update the portfolio's cashBalance and reservedCash
            portfolio.setCashBalance(portfolio.getCashBalance().subtract(actualCost));
            portfolio.setReservedCash(portfolio.getReservedCash().subtract(reservedCash));

            //update holdings
            tradeExecutionService.updateHoldingAfterBuy(portfolio, lockedOrder.getAsset(), lockedOrder.getQuantity(), latestPrice);

            //create BUY transaction
            tradeExecutionService.createTransaction(portfolio, lockedOrder.getAsset(), TransactionType.BUY, lockedOrder.getQuantity(), latestPrice, null);

            //update order status
            lockedOrder.setStatus(OrderStatus.FILLED);
            lockedOrder.setExecutedAt(LocalDateTime.now());

            //save EXECUTE_LIMIT_ORDER audit log
            auditLogService.logOrderExecution(lockedOrder, email,
                    "Executed BUY limit order #" + lockedOrder.getId()
                            + " for " + lockedOrder.getQuantity() + " shares of " + lockedOrder.getAsset().getSymbol()
            );

            //publish notification
            investmentEventService.saveInvestmentNotificationEvent( new InvestmentNotificationEvent(
                            email,
                            "Limit BUY order executed: " + lockedOrder.getQuantity() + " shares of " + lockedOrder.getAsset().getSymbol())
            );

            //send confirmation email for executed BUY limit order
            investmentEventService.saveTransactionEvent(
                    new TransactionCompletedEvent(
                            email, "BUY", lockedOrder.getAsset().getSymbol(), lockedOrder.getQuantity(), actualCost
                    )
            );

            //add log
            log.info("Limit BUY order executed - orderId: {}, asset: {}, quantity: {}, price: {}",
                    lockedOrder.getId(),
                    lockedOrder.getAsset().getSymbol(),
                    lockedOrder.getQuantity(),
                    latestPrice);

        }
        //SELL
        else if (lockedOrder.getOrderType() == TransactionType.SELL && currentPrice.compareTo(limitPrice) >= 0){
            //execute SELL limit orders
            //get user portfolio using locking method (portfolio locking)
            Portfolio portfolio = portfolioRepository.findByIdForUpdate(lockedOrder.getPortfolio().getId())
                    .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));

            //refresh portfolio to get the latest database values
            entityManager.refresh(portfolio);

            //lock asset to get latest price
            Asset asset = assetRepository.findByIdForUpdate(lockedOrder.getAsset().getId())
                    .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

            //get the latest price after locking the asset
            BigDecimal latestPrice = asset.getCurrentPrice();
            //check SELL limit price again
            if (latestPrice.compareTo(limitPrice) < 0) {
                return;
            }

            //get user holding
            Holding holding = holdingRepository.findByPortfolioAndAsset(portfolio, lockedOrder.getAsset())
                    .orElseThrow(() -> new BusinessRuleException("You do not own this asset"));
            //calculate the original cost of the sold shares
            BigDecimal costBasis = lockedOrder.getQuantity().multiply(holding.getAverageBuyPrice()).setScale(2, RoundingMode.HALF_UP);

            //calculate sale proceeds
            BigDecimal proceeds = lockedOrder.getQuantity().multiply(latestPrice).setScale(2, RoundingMode.HALF_UP);

            //release reserved shares
            if (holding.getReservedQuantity().compareTo(lockedOrder.getQuantity()) < 0) {
                throw new BusinessRuleException("Insufficient reserved shares");
            }
            holding.setReservedQuantity(holding.getReservedQuantity().subtract(lockedOrder.getQuantity()));

            //reduce the holding using the shared service
            tradeExecutionService.updateHoldingAfterSell(portfolio, asset, lockedOrder.getQuantity());

            //increase the portfolio cash balance
            portfolio.setCashBalance(portfolio.getCashBalance().add(proceeds));

            //create SELL transaction
            tradeExecutionService.createTransaction(portfolio, lockedOrder.getAsset(), TransactionType.SELL, lockedOrder.getQuantity(), latestPrice, costBasis);

            //update order status
            lockedOrder.setStatus(OrderStatus.FILLED);
            lockedOrder.setExecutedAt(LocalDateTime.now());

            //save EXECUTE_LIMIT_ORDER audit log
             auditLogService.logOrderExecution(lockedOrder, email,
                    "Executed SELL limit order #" + lockedOrder.getId()
                            + " for " + lockedOrder.getQuantity() + " shares of " + lockedOrder.getAsset().getSymbol()
            );


            //publish notification
            investmentEventService.saveInvestmentNotificationEvent( new InvestmentNotificationEvent(
                    email,
                    "Limit SELL order executed: " + lockedOrder.getQuantity()
                            + " shares of " + lockedOrder.getAsset().getSymbol())
            );

            //send confirmation email for executed SELL limit order
            investmentEventService.saveTransactionEvent(
                    new TransactionCompletedEvent(
                            email, "SELL", lockedOrder.getAsset().getSymbol(), lockedOrder.getQuantity(), proceeds
                    )
            );

            //add long
            log.info("Limit SELL order executed - orderId: {}, asset: {}, quantity: {}, price: {}",
                    lockedOrder.getId(),
                    lockedOrder.getAsset().getSymbol(),
                    lockedOrder.getQuantity(),
                    latestPrice);

        }
    }

}
