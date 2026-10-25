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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class MarketSimulationService {
    private final AssetRepository assetRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final MarketPriceUpdateService marketPriceUpdateService;
    private static final Logger log = LoggerFactory.getLogger(MarketSimulationService.class);


    //track price changes that have been submitted to thread pool but haven't finished processing
    private final Set<Long> processingAssetIds = ConcurrentHashMap.newKeySet();

    @Qualifier("marketExecutor")
    private final ThreadPoolTaskExecutor marketExecutor;

    //scheduled method to automatically update asset prices every 10s
    @Scheduled(fixedDelay = 10000)
    public void updateMarketPrices() {
        //retrieve active assets
        List<Asset> assets = assetRepository.findByAssetStatus(AssetStatus.ACTIVE);

        //calculate each asset's new price
        for (Asset asset : assets) {
            Long assetId = asset.getId();

            //check whether price change is added to the asset tracker to prevent processing it twice
            if (!processingAssetIds.add(assetId)){
                continue;
            }

            try{
                marketExecutor.execute(() -> {
                    try {
                        //update asset price
                        marketPriceUpdateService.updateAssetPrice(assetId);
                    } catch (Exception e) {
                        log.error("Failed to update Asset ID: {}", assetId, e);
                    } finally {
                        //remove from the asset tracker
                        processingAssetIds.remove(assetId);
                    }
                });
            }catch (Exception e) {
                //remove from the asset processing tracker if pool is full and rejects order
                processingAssetIds.remove(assetId);
                log.error("Failed to execute Asset ID: {}", assetId, e);
            }


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
