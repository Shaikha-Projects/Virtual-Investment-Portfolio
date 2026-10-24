package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Enums.TransactionStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Exception.BusinessRuleException;
import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.Holding;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.Transaction;
import com.ga.investmentportfolio.Repository.HoldingRepository;
import com.ga.investmentportfolio.Repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TradeExecutionService {
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;

    //method to update holdings after buy operation
    public void updateHoldingAfterBuy(Portfolio portfolio, Asset asset, BigDecimal quantity, BigDecimal price) {
        //check if the holding already exists
        Optional<Holding> existingHolding = holdingRepository.findByPortfolioAndAsset(portfolio, asset);

        if (existingHolding.isEmpty()) {
            //create first holding
            Holding holding = new Holding();
            holding.setPortfolio(portfolio);
            holding.setAsset(asset);
            holding.setQuantity(quantity);
            holding.setAverageBuyPrice(price);
            holding.setReservedQuantity(BigDecimal.ZERO);

            //save holding
            holdingRepository.save(holding);
        } else {

            //if holding already exists update its fields
            Holding holding = existingHolding.get();

            //calculate the old and new investment and total
            BigDecimal oldInvestment = holding.getQuantity().multiply(holding.getAverageBuyPrice());
            BigDecimal newInvestment = quantity.multiply(price);
            BigDecimal sum = oldInvestment.add(newInvestment);

            //calculate new quantity and avg
            BigDecimal newQuantity = holding.getQuantity().add(quantity);
            BigDecimal newAverageBuyPrice = sum.divide(newQuantity, 2, RoundingMode.HALF_UP);

            //update fields and save
            holding.setQuantity(newQuantity);
            holding.setAverageBuyPrice(newAverageBuyPrice);
            holdingRepository.save(holding);
        }
    }

    //method to update holdings after a SELL
    public void updateHoldingAfterSell(Portfolio portfolio, Asset asset, BigDecimal quantity) {
        //find the holding
        Holding holding = holdingRepository.findByPortfolioAndAsset(portfolio, asset)
                .orElseThrow(() -> new BusinessRuleException("You do not own this asset"));

        //calculate remaining quantity
        BigDecimal remainingQuantity = holding.getQuantity().subtract(quantity);

        //prevent negative holdings
        if (remainingQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException("Insufficient quantity to sell");
        }

        //prevent selling shares reserved for other orders
        if (remainingQuantity.compareTo(holding.getReservedQuantity()) < 0) {
            throw new BusinessRuleException("Cannot sell shares reserved for pending orders");
        }

        if (remainingQuantity.compareTo(BigDecimal.ZERO) == 0) {
            //user sold all shares
            holdingRepository.delete(holding);
        } else {
            //user still owns some shares
            holding.setQuantity(remainingQuantity);
            holdingRepository.save(holding);
        }
    }

    //method to create transaction after buy/sell operation
    public Transaction createTransaction(Portfolio portfolio, Asset asset, TransactionType type, BigDecimal quantity, BigDecimal price) {
        BigDecimal totalAmount = quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);

        Transaction transaction = new Transaction();

        transaction.setTransactionType(type);
        transaction.setQuantity(quantity);
        transaction.setPricePerUnit(price);
        transaction.setTotalAmount(totalAmount);
        transaction.setTransactionStatus(TransactionStatus.COMPLETED);
        transaction.setPortfolio(portfolio);
        transaction.setAsset(asset);

        return transactionRepository.save(transaction);
    }

}
