package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.PriceHistory;
import com.ga.investmentportfolio.Repository.AssetRepository;
import com.ga.investmentportfolio.Repository.PriceHistoryRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class MarketPriceUpdateService {
    private final AssetRepository assetRepository;
    private final PriceHistoryRepository priceHistoryRepository;

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

    //method to update asset price one at a time within its own database transaction
    @Transactional
    public void updateAssetPrice(Long assetId){
        //find asset
        Asset asset = assetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        //skip price update if asset is inactive
        if (asset.getAssetStatus() != AssetStatus.ACTIVE) {
            return;
        }

            //calculate new price
        BigDecimal newPrice = calculateNewPrice(asset.getCurrentPrice());

        //update asset price and save it
        asset.setCurrentPrice(newPrice);
        assetRepository.save(asset);

        //save price history record
        PriceHistory history = new PriceHistory();
        history.setAsset(asset);
        history.setPrice(newPrice);
        priceHistoryRepository.save(history);

    }
}
