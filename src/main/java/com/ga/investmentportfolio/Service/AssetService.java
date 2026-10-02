package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AssetService {
    private final AssetRepository assetRepository;

    public List<AssetResponse> getActiveAssets() {
        //find active assets
        List<Asset> activeAssets = assetRepository.findByAssetStatus(AssetStatus.ACTIVE);

        //return activeAssets using AssetResponse stream/map
        return activeAssets.stream().map(asset -> new  AssetResponse(asset.getSymbol(), asset.getName(),
                asset.getAssetType(), asset.getCurrentPrice())).toList();
    }
}
