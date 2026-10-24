package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.CreateOrderRequest;
import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.DTO.Response.InvestmentOrderResponse;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Exception.BusinessRuleException;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Model.*;
import com.ga.investmentportfolio.Repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class InvestmentOrderService {
    private final InvestmentOrderRepository investmentOrderRepository;
    private final UserRepository userRepository;
    private final PortfolioRepository portfolioRepository;
    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final TradeExecutionService tradeExecutionService;
    private final OrderExecutionService orderExecutionService;
    private static final Logger log = LoggerFactory.getLogger(InvestmentOrderService.class);

    //track orders that have been submitted to thread pool but haven't finished processing
    private final Set<Long> processingOrderIds = ConcurrentHashMap.newKeySet();

    @Qualifier("orderExecutor") //to specify bean
    private final ThreadPoolTaskExecutor orderExecutor;

    @PersistenceContext
    private EntityManager entityManager;

    //method to place order limit
    @Transactional
    public void placeLimitOrder(String email, CreateOrderRequest request){
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user portfolio using locking method (portfolio locking)
        Portfolio portfolio = portfolioRepository.findByIdForUpdate(user.getPortfolio().getId())
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


    }

    //method to get all investment orders
    public List<InvestmentOrderResponse> getMyOrders(String email){
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user portfolio
        Portfolio portfolio = user.getPortfolio();

        //get user's orders
        List<InvestmentOrder> investmentOrders = investmentOrderRepository.findByPortfolioOrderByCreatedAtDesc(portfolio);

        //return investmentOrders using InvestmentOrderResponse stream/map
        return investmentOrders.stream().map(investmentOrder -> new InvestmentOrderResponse(investmentOrder.getId(),
                        investmentOrder.getAsset().getId(), investmentOrder.getOrderType(),
                        investmentOrder.getQuantity(), investmentOrder.getLimitPrice(),
                        investmentOrder.getStatus(), investmentOrder.getCreatedAt(),
                        investmentOrder.getExecutedAt())).toList();

    }

    //method to cancel an order
    @Transactional
    public void cancelOrder(String email, Long orderId) {
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //find investment order using database locking
        InvestmentOrder order = investmentOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new InformationNotFoundException("Investment order not found"));

        //verify order belongs to the user
        if (!order.getPortfolio().getId().equals(user.getPortfolio().getId())) {
            throw new BusinessRuleException("You cannot cancel this order");
        }

        //get user portfolio using locking method (portfolio locking)
        Portfolio portfolio = portfolioRepository.findByIdForUpdate(user.getPortfolio().getId())
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
                        //remove from the order processing traker
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
