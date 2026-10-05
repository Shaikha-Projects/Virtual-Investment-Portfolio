package com.ga.investmentportfolio.Config;

import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Repository.AssetRepository;
import jakarta.activation.CommandMap;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
@RequiredArgsConstructor
public class AssetDataInitializer implements CommandLineRunner {
    private final AssetRepository assetRepository;

    @Override
    public void run(String... args) {

        //if AAPL asset does not exist, create it
        if (!assetRepository.existsBySymbolIgnoreCase("AAPL")){
            Asset asset = new Asset();
            asset.setSymbol("AAPL");
            asset.setName("Apple Inc.");
            asset.setAssetType(AssetType.STOCK);
            asset.setCurrentPrice(new BigDecimal("300.00"));
            asset.setAssetStatus(AssetStatus.ACTIVE);
            assetRepository.save(asset);
        }

        //if MSFT asset does not exist, create it
        if (!assetRepository.existsBySymbolIgnoreCase("MSFT")) {
            Asset asset = new Asset();
            asset.setSymbol("MSFT");
            asset.setName("Microsoft Corporation");
            asset.setAssetType(AssetType.STOCK);
            asset.setCurrentPrice(new BigDecimal("450.00"));
            asset.setAssetStatus(AssetStatus.ACTIVE);
            assetRepository.save(asset);
        }

        //if TSLA asset does not exist, create it
        if (!assetRepository.existsBySymbolIgnoreCase("TSLA")) {
            Asset asset = new Asset();
            asset.setSymbol("TSLA");
            asset.setName("Tesla Inc.");
            asset.setAssetType(AssetType.STOCK);
            asset.setCurrentPrice(new BigDecimal("400.00"));
            asset.setAssetStatus(AssetStatus.ACTIVE);
            assetRepository.save(asset);
        }

    }
}
