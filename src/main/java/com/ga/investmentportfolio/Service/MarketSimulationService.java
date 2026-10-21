package com.ga.investmentportfolio.Service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class MarketSimulationService {

    //method to calculate random price changes
    public BigDecimal calculateNewPrice(BigDecimal currentPrice) {
        //generate random change between (−0.5% and +0.5%(excluded))
        double change = ThreadLocalRandom.current().nextDouble(-0.005, 0.005);

        //convert change
        BigDecimal changeDecimal = BigDecimal.valueOf(change);

        //calculate New Price = Current Price × (1 + Change)
        BigDecimal multiplier = BigDecimal.ONE.add(changeDecimal);
        BigDecimal newPrice = currentPrice.multiply(multiplier);

        //round the price to two decimal points
        newPrice = newPrice.setScale(2, RoundingMode.HALF_UP);

        //safeguard to ensure the price never becomes 0.00
        if (newPrice.compareTo(new BigDecimal("0.01")) < 0) {
            newPrice = new BigDecimal("0.01");
        }

        //return price
        return newPrice;

    }

}
