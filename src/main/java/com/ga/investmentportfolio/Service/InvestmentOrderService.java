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
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InvestmentOrderService {
    private final InvestmentOrderRepository investmentOrderRepository;
    private final UserRepository userRepository;
    private final PortfolioRepository portfolioRepository;
    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;
    private final TradeExecutionService tradeExecutionService;

    //method to place order limit
    @Transactional
    public void placeLimitOrder(String email, CreateOrderRequest request){
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user portfolio
        Portfolio portfolio = user.getPortfolio();

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
            BigDecimal requiredCash = request.getQuantity().multiply(request.getLimitPrice());

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

        //find investment order
        InvestmentOrder order = investmentOrderRepository.findById(orderId)
                .orElseThrow(() -> new InformationNotFoundException("Investment order not found"));

        //verify order belongs to the user
        if (!order.getPortfolio().getId().equals(user.getPortfolio().getId())) {
            throw new BusinessRuleException("You cannot cancel this order");
        }

        //check order status
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessRuleException("Only pending orders can be cancelled");
        }

        //BUY order
        //release reserved cash(portfolio)
        if(order.getOrderType() == TransactionType.BUY){
            //get user portfolio
            Portfolio portfolio = user.getPortfolio();

            //calculates the reserved amount
            BigDecimal requiredCash = order.getQuantity().multiply(order.getLimitPrice());

            //update reservedCash
            portfolio.setReservedCash(portfolio.getReservedCash().subtract(requiredCash));

        }
        //SELL order
        //release reserved shares (holdings)
        else if (order.getOrderType() == TransactionType.SELL) {
                //get user portfolio
                Portfolio portfolio = user.getPortfolio();

                //find the holding
                Holding holding = holdingRepository.findByPortfolioAndAsset(portfolio, order.getAsset())
                        .orElseThrow(() -> new BusinessRuleException("You do not own this asset"));

                //get reserved quantity
                BigDecimal reservedQuantity = order.getQuantity();

                //update reserved quantity
                holding.setReservedQuantity(holding.getReservedQuantity().subtract(reservedQuantity));
        }


        //change status to CANCELLED
        order.setStatus(OrderStatus.CANCELLED);
        investmentOrderRepository.save(order);

    }

    //method to execute pending orders weather they are buy/sell
    @Transactional
    public void executePendingOrders() {
        //retrieve all pending orders
        List<InvestmentOrder> pendingOrders = investmentOrderRepository.findByStatus(OrderStatus.PENDING);

        //to process each order
        for(InvestmentOrder order : pendingOrders){
            //get asset current price and order's limit price
            BigDecimal currentPrice = order.getAsset().getCurrentPrice();
            BigDecimal limitPrice = order.getLimitPrice();

            //BUY
            if(order.getOrderType() == TransactionType.BUY && currentPrice.compareTo(limitPrice) <= 0){
                //execute BUY limit orders
                //get user portfolio
                Portfolio portfolio = order.getPortfolio();

                BigDecimal actualCost = order.getQuantity().multiply(currentPrice);
                BigDecimal reservedCash = order.getQuantity().multiply(limitPrice);

                //update the portfolio's cashBalance and reservedCash
                portfolio.setCashBalance(portfolio.getCashBalance().subtract(actualCost));
                portfolio.setReservedCash(portfolio.getReservedCash().subtract(reservedCash));

                //update holdings
                tradeExecutionService.updateHoldingAfterBuy(portfolio, order.getAsset(), order.getQuantity(), currentPrice);

                //create BUY transaction
                tradeExecutionService.createTransaction(portfolio, order.getAsset(), TransactionType.BUY, order.getQuantity(), currentPrice);

                //update order status
                order.setStatus(OrderStatus.FILLED);
                order.setExecutedAt(LocalDateTime.now());
            }
            //SELL
            else if (order.getOrderType() == TransactionType.SELL && currentPrice.compareTo(limitPrice) >= 0){
                //execute SELL limit orders
                //get user portfolio and holding
                Portfolio portfolio = order.getPortfolio();
                Holding holding = holdingRepository.findByPortfolioAndAsset(portfolio, order.getAsset())
                        .orElseThrow(() -> new BusinessRuleException("You do not own this asset"));

                //calculate sale proceeds
                BigDecimal proceeds = order.getQuantity().multiply(currentPrice);

                //release reserved shares
                if (holding.getReservedQuantity().compareTo(order.getQuantity()) < 0) {
                    throw new BusinessRuleException("Insufficient reserved shares");
                }
                holding.setReservedQuantity(holding.getReservedQuantity().subtract(order.getQuantity()));

                //reduce the holding using the shared service
                tradeExecutionService.updateHoldingAfterSell(portfolio, order.getAsset(), order.getQuantity());

                //increase the portfolio cash balance
                portfolio.setCashBalance(portfolio.getCashBalance().add(proceeds));

                //create SELL transaction
                tradeExecutionService.createTransaction(portfolio, order.getAsset(), TransactionType.SELL, order.getQuantity(), currentPrice);

                //update order status
                order.setStatus(OrderStatus.FILLED);
                order.setExecutedAt(LocalDateTime.now());

            }
        }
    }
}
