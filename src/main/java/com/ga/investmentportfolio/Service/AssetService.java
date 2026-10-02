package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Enums.AssetType;
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

    public List<AssetResponse> searchAssets(String search, AssetType assetType){
        //store result
        List<Asset> assets;

        if(search != null && assetType != null ){
            //search + assetType == search by symbol/name and filter by type
            assets = assetRepository.searchByType(AssetStatus.ACTIVE, assetType, search);
        } else if (search != null)  {
            //search provided + no type == search by symbol or name
            assets = assetRepository.findByAssetStatusAndSymbolContainingIgnoreCaseOrAssetStatusAndNameContainingIgnoreCase(
                    AssetStatus.ACTIVE, search, AssetStatus.ACTIVE, search
            );
        } else if (assetType != null) {
            //no search + type provided == filter by asset type
            assets = assetRepository.findByAssetStatusAndAssetType(AssetStatus.ACTIVE, assetType);
        } else {
            //no search, no type == all active assets
            assets = assetRepository.findByAssetStatus(AssetStatus.ACTIVE);
        }

        return assets.stream().map(asset -> new  AssetResponse(asset.getSymbol(), asset.getName(),
                asset.getAssetType(), asset.getCurrentPrice())).toList();


    }
}
