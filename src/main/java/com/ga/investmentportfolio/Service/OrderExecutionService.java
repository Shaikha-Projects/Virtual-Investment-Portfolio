package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Enums.AuditAction;
import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Event.InvestmentNotificationEvent;
import com.ga.investmentportfolio.Event.TransactionEmailEvent;
import com.ga.investmentportfolio.Exception.BusinessRuleException;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Model.Holding;
import com.ga.investmentportfolio.Model.InvestmentOrder;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;

    @PersistenceContext
    private EntityManager entityManager;

    //method to execute each order in its transaction
    @Transactional
    public void executeSingleOrder(Long orderId) {
        // add log for testing
        log.info("Starting order execution - orderId: {}, thread: {}", orderId, Thread.currentThread().getName());

        //retrieve order
        InvestmentOrder lockedOrder = investmentOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new InformationNotFoundException("Order not found"));

        //check order status after locking
        if (lockedOrder.getStatus() != OrderStatus.PENDING) {
            return;
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

            BigDecimal actualCost = lockedOrder.getQuantity().multiply(currentPrice).setScale(2, RoundingMode.HALF_UP);
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
            tradeExecutionService.updateHoldingAfterBuy(portfolio, lockedOrder.getAsset(), lockedOrder.getQuantity(), currentPrice);

            //create BUY transaction
            tradeExecutionService.createTransaction(portfolio, lockedOrder.getAsset(), TransactionType.BUY, lockedOrder.getQuantity(), currentPrice);

            //update order status
            lockedOrder.setStatus(OrderStatus.FILLED);
            lockedOrder.setExecutedAt(LocalDateTime.now());

            //save EXECUTE_LIMIT_ORDER audit log
            auditLogService.log(lockedOrder.getPortfolio().getUser().getEmailAddress(), AuditAction.EXECUTE_LIMIT_ORDER,
                    "Executed BUY limit order #" + lockedOrder.getId()
                            + " for " + lockedOrder.getQuantity() + " shares of " + lockedOrder.getAsset().getSymbol()
            );

            //publish notification
            eventPublisher.publishEvent( new InvestmentNotificationEvent(
                            lockedOrder.getPortfolio().getUser().getEmailAddress(),
                            "Limit BUY order executed: " + lockedOrder.getQuantity() + " shares of " + lockedOrder.getAsset().getSymbol())
            );

            //send confirmation email for executed BUY limit order
            eventPublisher.publishEvent(
                    new TransactionEmailEvent(
                            lockedOrder.getPortfolio().getUser().getEmailAddress(), "BUY", lockedOrder.getAsset().getSymbol(), lockedOrder.getQuantity(), actualCost
                    )
            );

            //add log
            log.info("Limit BUY order executed - orderId: {}, asset: {}, quantity: {}, price: {}",
                    lockedOrder.getId(),
                    lockedOrder.getAsset().getSymbol(),
                    lockedOrder.getQuantity(),
                    currentPrice);

        }
        //SELL
        else if (lockedOrder.getOrderType() == TransactionType.SELL && currentPrice.compareTo(limitPrice) >= 0){
            //execute SELL limit orders
            //get user portfolio using locking method (portfolio locking)
            Portfolio portfolio = portfolioRepository.findByIdForUpdate(lockedOrder.getPortfolio().getId())
                    .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));

            //refresh portfolio to get the latest database values
            entityManager.refresh(portfolio);

            //get user holding
            Holding holding = holdingRepository.findByPortfolioAndAsset(portfolio, lockedOrder.getAsset())
                    .orElseThrow(() -> new BusinessRuleException("You do not own this asset"));

            //calculate sale proceeds
            BigDecimal proceeds = lockedOrder.getQuantity().multiply(currentPrice).setScale(2, RoundingMode.HALF_UP);

            //release reserved shares
            if (holding.getReservedQuantity().compareTo(lockedOrder.getQuantity()) < 0) {
                throw new BusinessRuleException("Insufficient reserved shares");
            }
            holding.setReservedQuantity(holding.getReservedQuantity().subtract(lockedOrder.getQuantity()));

            //reduce the holding using the shared service
            tradeExecutionService.updateHoldingAfterSell(portfolio, lockedOrder.getAsset(), lockedOrder.getQuantity());

            //increase the portfolio cash balance
            portfolio.setCashBalance(portfolio.getCashBalance().add(proceeds));

            //create SELL transaction
            tradeExecutionService.createTransaction(portfolio, lockedOrder.getAsset(), TransactionType.SELL, lockedOrder.getQuantity(), currentPrice);

            //update order status
            lockedOrder.setStatus(OrderStatus.FILLED);
            lockedOrder.setExecutedAt(LocalDateTime.now());

            //save EXECUTE_LIMIT_ORDER audit log
            auditLogService.log(lockedOrder.getPortfolio().getUser().getEmailAddress(), AuditAction.EXECUTE_LIMIT_ORDER,
                    "Executed SELL limit order #" + lockedOrder.getId()
                            + " for " + lockedOrder.getQuantity() + " shares of " + lockedOrder.getAsset().getSymbol()
            );


            //publish notification
            eventPublisher.publishEvent( new InvestmentNotificationEvent(
                    lockedOrder.getPortfolio().getUser().getEmailAddress(),
                    "Limit SELL order executed: " + lockedOrder.getQuantity()
                            + " shares of " + lockedOrder.getAsset().getSymbol())
            );

            //send confirmation email for executed SELL limit order
            eventPublisher.publishEvent(
                    new TransactionEmailEvent(
                            lockedOrder.getPortfolio().getUser().getEmailAddress(), "SELL", lockedOrder.getAsset().getSymbol(), lockedOrder.getQuantity(), proceeds
                    )
            );

            //add long
            log.info("Limit SELL order executed - orderId: {}, asset: {}, quantity: {}, price: {}",
                    lockedOrder.getId(),
                    lockedOrder.getAsset().getSymbol(),
                    lockedOrder.getQuantity(),
                    currentPrice);

        }
    }

}
