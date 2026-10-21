package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Response.PriceHistoryResponse;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.PriceHistory;
import com.ga.investmentportfolio.Repository.AssetRepository;
import com.ga.investmentportfolio.Repository.PriceHistoryRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class MarketSimulationService {
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


    //scheduled method to automatically update asset prices every 10s
    @Transactional
    @Scheduled(fixedDelay = 10000)
    public void updateMarketPrices() {
        //retrieve active assets
        List<Asset> assets = assetRepository.findByAssetStatus(AssetStatus.ACTIVE);

        //calculate each asset's new price
        for (Asset asset : assets) {
            BigDecimal newPrice = calculateNewPrice(asset.getCurrentPrice());
            asset.setCurrentPrice(newPrice);

            //save the prices
            assetRepository.save(asset);

            //save a PriceHistory record
            PriceHistory history = new PriceHistory();
            history.setAsset(asset);
            history.setPrice(newPrice);
            priceHistoryRepository.save(history);
        }

    }

    //method to retrieve price history for an asset
    @Transactional
    public List<PriceHistoryResponse> getPriceHistory(Long assetId) {
        //retrieve asset's price history
        List<PriceHistory> histories = priceHistoryRepository.findByAssetIdOrderByRecordedAtAsc(assetId);

        //convert each record to response
        List<PriceHistoryResponse> responses = new ArrayList<>();

        for (PriceHistory history : histories) {
            //create PriceHistoryResponse
            PriceHistoryResponse response = new PriceHistoryResponse(history.getPrice(), history.getRecordedAt());

            //add it to the list
            responses.add(response);
        }

        return responses;

    }

}
