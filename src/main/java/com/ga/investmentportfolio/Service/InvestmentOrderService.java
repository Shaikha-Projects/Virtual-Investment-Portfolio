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
import java.util.List;

@Service
@RequiredArgsConstructor
public class InvestmentOrderService {
    private final InvestmentOrderRepository investmentOrderRepository;
    private final UserRepository userRepository;
    private final PortfolioRepository portfolioRepository;
    private final AssetRepository assetRepository;
    private final HoldingRepository holdingRepository;

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
}
