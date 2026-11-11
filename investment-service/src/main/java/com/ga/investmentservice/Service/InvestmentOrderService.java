package com.ga.investmentservice.Service;

import com.ga.investmentservice.DTO.Request.CreateOrderRequest;
import com.ga.investmentservice.DTO.Response.InvestmentOrderResponse;
import com.ga.investmentservice.Enums.AssetStatus;
import com.ga.investmentservice.Enums.AuditAction;
import com.ga.investmentservice.Enums.OrderStatus;
import com.ga.investmentservice.Enums.TransactionType;
import com.ga.investmentservice.Event.InvestmentNotificationEvent;
import com.ga.investmentservice.Exception.BusinessRuleException;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import com.ga.investmentservice.Model.*;
import com.ga.investmentservice.Repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.ga.investmentservice.Security.CurrentUser;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvestmentOrderService {
    private final InvestmentOrderRepository investmentOrderRepository;
    private final CurrentUser currentUser;
    private final PortfolioRepository portfolioRepository;
    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final TradeExecutionService tradeExecutionService;
    private final OrderExecutionService orderExecutionService;
    //private static final Logger log = LoggerFactory.getLogger(InvestmentOrderService.class);
    private final InvestmentEventService investmentEventService;
    private final AuditLogService auditLogService;


    //track orders that have been submitted to thread pool but haven't finished processing
    private final Set<Long> processingOrderIds = ConcurrentHashMap.newKeySet();

    @Qualifier("orderExecutor") //to specify bean
    private final ThreadPoolTaskExecutor orderExecutor;

    @PersistenceContext
    private EntityManager entityManager;

    //method to place order limit
    @Transactional
    @PreAuthorize("hasRole('USER')")
    public void placeLimitOrder(String email, CreateOrderRequest request){
        //find user by email
        Long userId = currentUser.getUserId(email);

        //get user portfolio using locking method (portfolio locking)
        Portfolio portfolio = portfolioRepository.findByIdForUpdate(portfolioRepository.findByUserId(userId)
                        .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist")).getId())
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));

        //refresh portfolio to get the latest database values
        entityManager.refresh(portfolio);

        //find asset
        Asset asset = assetRepository.findById(request.getAssetId())
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        //check asset status
        if(asset.getAssetStatus() != AssetStatus.ACTIVE){
            throw new BusinessRuleException("Asset is not available for trading");
        }

        //BUY reservations
        if (request.getOrderType() == TransactionType.BUY) {
            BigDecimal availableCash = portfolio.getCashBalance().subtract(portfolio.getReservedCash());
            BigDecimal requiredCash = request.getQuantity().multiply(request.getLimitPrice()) .setScale(2, RoundingMode.HALF_UP);

            if(availableCash.compareTo(requiredCash) < 0){
                throw new BusinessRuleException("Insufficient available cash for this order");
            }

            //update reserved cash
            portfolio.setReservedCash(portfolio.getReservedCash().add(requiredCash));

        }

        //SELL reservations
        if (request.getOrderType() == TransactionType.SELL) {

            //find the holding
            Holding holding = holdingRepository.findByPortfolioAndAsset(portfolio, asset)
                    .orElseThrow(() -> new BusinessRuleException("You do not own this asset"));

            BigDecimal availableQuantity = holding.getQuantity().subtract(holding.getReservedQuantity());
            BigDecimal reservedQuantity = request.getQuantity();

            if(availableQuantity.compareTo(reservedQuantity) < 0){
                throw new BusinessRuleException("Insufficient available shares for this order");
            }

            //update reserved quantity
            holding.setReservedQuantity(holding.getReservedQuantity().add(reservedQuantity));

        }

        //create investment order object
        InvestmentOrder order = new InvestmentOrder();

        order.setOrderType(request.getOrderType());
        order.setQuantity(request.getQuantity());
        order.setLimitPrice(request.getLimitPrice());
        order.setStatus(OrderStatus.PENDING);
        order.setPortfolio(portfolio);
        order.setAsset(asset);

        //save investment order
        investmentOrderRepository.save(order);

        //add log
        log.info("Limit order placed - orderId: {}, type: {}, asset: {}, quantity: {}",
                order.getId(),
                order.getOrderType(),
                order.getAsset().getSymbol(),
                order.getQuantity());

        //add an audit record
        auditLogService.log(email, AuditAction.PLACE_LIMIT_ORDER,
                "Placed " + order.getOrderType() + " limit order #" + order.getId()
                        + " for " + order.getQuantity() + " shares of " + order.getAsset().getSymbol());

    }

    //method to get all investment orders
    @PreAuthorize("hasRole('USER')")
    public Page<InvestmentOrderResponse> getMyOrders(String email, int page, int size){
        //find user by email
        Long userId = currentUser.getUserId(email);

        //get user's orders
        Pageable pageable = PageRequest.of(page, size);

        Page<InvestmentOrder> investmentOrders = investmentOrderRepository.findByPortfolio_UserIdOrderByCreatedAtDesc(userId, pageable);

        //return investmentOrders using InvestmentOrderResponse stream/map
        return investmentOrders.map(investmentOrder -> new InvestmentOrderResponse(investmentOrder.getId(),
                        investmentOrder.getAsset().getId(), investmentOrder.getOrderType(),
                        investmentOrder.getQuantity(), investmentOrder.getLimitPrice(),
                        investmentOrder.getStatus(), investmentOrder.getCreatedAt(),
                        investmentOrder.getExecutedAt()));

    }

    //method to cancel an order
    @Transactional
    @PreAuthorize("hasRole('USER')")
    public void cancelOrder(String email, Long orderId) {
        //find user by email
        Long userId = currentUser.getUserId(email);

        //find investment order using database locking
        InvestmentOrder order = investmentOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new InformationNotFoundException("Investment order not found"));

        //verify order belongs to the user
        if (!order.getPortfolio().getUserId().equals(userId)) {
            throw new BusinessRuleException("You cannot cancel this order");
        }

        //get user portfolio using locking method (portfolio locking)
        Portfolio portfolio = portfolioRepository.findByIdForUpdate(portfolioRepository.findByUserId(userId)
                        .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist")).getId())
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));

        //refresh portfolio to get the latest database values
        entityManager.refresh(portfolio);

        //check order status
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessRuleException("Only pending orders can be cancelled");
        }

        //BUY order
        //release reserved cash(portfolio)
        if(order.getOrderType() == TransactionType.BUY){
            //calculates the reserved amount
            BigDecimal requiredCash = order.getQuantity().multiply(order.getLimitPrice()) .setScale(2, RoundingMode.HALF_UP);

            //check sufficient reserved cash before cancellation
            if (portfolio.getReservedCash().compareTo(requiredCash) < 0) {
                throw new BusinessRuleException("Insufficient reserved cash");
            }

            //update reservedCash
            portfolio.setReservedCash(portfolio.getReservedCash().subtract(requiredCash));
        }
        //SELL order
        //release reserved shares (holdings)
        else if (order.getOrderType() == TransactionType.SELL) {
            //find the holding using database locking
            Holding holding = holdingRepository.findByPortfolioAndAsset(portfolio, order.getAsset())
                    .orElseThrow(() -> new BusinessRuleException("You do not own this asset"));

            //get reserved quantity
            BigDecimal reservedQuantity = order.getQuantity();

            //check sufficient reserved shares before cancellation
            if (holding.getReservedQuantity().compareTo(reservedQuantity) < 0) {
                throw new BusinessRuleException("Insufficient reserved shares");
            }

            //update reserved quantity
            holding.setReservedQuantity(holding.getReservedQuantity().subtract(reservedQuantity));
        }


        //change status to CANCELLED
        order.setStatus(OrderStatus.CANCELLED);
        investmentOrderRepository.save(order);

        //save cancellation audit log
        auditLogService.log(email, AuditAction.CANCEL_LIMIT_ORDER,
                "Cancelled limit order #" + order.getId()
                        + " for " + order.getQuantity() + " shares of " + order.getAsset().getSymbol()
        );

        //publish notification
        investmentEventService.saveInvestmentNotificationEvent( new InvestmentNotificationEvent(
                email,
                "Limit order cancelled: " + order.getQuantity()
                        + " shares of " + order.getAsset().getSymbol())
        );

        //add log
        log.info("Limit order cancelled - orderId: {}, asset: {}, quantity: {}",
                order.getId(),
                order.getAsset().getSymbol(),
                order.getQuantity());

    }

    //method to execute pending orders whether they are buy/sell(finds pending orders)
    @Scheduled(fixedDelay = 10000) //wait 10s after each execution finishes before running again
    public void executePendingOrders() {
        //retrieve all pending orders
        List<InvestmentOrder> pendingOrders = investmentOrderRepository.findByStatus(OrderStatus.PENDING);

        //execute each order in its own transaction
        for(InvestmentOrder order : pendingOrders){
            //check whether order is added to the order tracker to prevent processing it twice
            if(!processingOrderIds.add(order.getId())){
                continue;
            }

            try {
                orderExecutor.execute(()-> {
                    try{
                        orderExecutionService.executeSingleOrder(order.getId());
                    }catch (Exception e) {
                        log.error("Failed to execute order ID: {}", order.getId(), e);
                    } finally {
                        //remove from the order processing tracker
                        processingOrderIds.remove(order.getId());
                    }

                });

            } catch (Exception e) {
                //remove from the order processing tracker if pool is full and rejects order
                processingOrderIds.remove(order.getId());
                log.error("Failed to execute order ID: {}", order.getId(), e);
            }
        }
    }
}
