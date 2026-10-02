package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.CreateAssetRequest;
import com.ga.investmentportfolio.DTO.Request.UpdateAssetRequest;
import com.ga.investmentportfolio.DTO.Request.UpdateAssetStatusRequest;
import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Exception.InformationExistException;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
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
                asset.getAssetType(), asset.getCurrentPrice(), asset.getAssetStatus())).toList();
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
                asset.getAssetType(), asset.getCurrentPrice(),asset.getAssetStatus())).toList();


    }

    public AssetResponse createAsset(CreateAssetRequest request){
        //if symbol already exists, throw existing
        if(assetRepository.existsBySymbolIgnoreCase(request.getSymbol())){
            throw new InformationExistException("Asset symbol already exists");
        }

        //create asset
        Asset asset = new Asset();
        asset.setSymbol(request.getSymbol().trim().toUpperCase());
        asset.setName(request.getName());
        asset.setAssetType(request.getAssetType());
        asset.setCurrentPrice(request.getCurrentPrice());
        asset.setAssetStatus(AssetStatus.ACTIVE);

        //save asset
        Asset createdAsset = assetRepository.save(asset);

        return new AssetResponse(createdAsset.getSymbol(), createdAsset.getName(),
                createdAsset.getAssetType(), createdAsset.getCurrentPrice(), createdAsset.getAssetStatus());
    }

    public AssetResponse updateAsset(Long assetId, UpdateAssetRequest request){
        //find asset by id
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        //find if asset symbol already exist
        if(assetRepository.existsBySymbolIgnoreCaseAndIdNot(request.getSymbol(), assetId)){
            throw new InformationExistException("Asset symbol already exists");
        }

        //update existing asset
        asset.setSymbol(request.getSymbol().trim().toUpperCase());
        asset.setName(request.getName());
        asset.setAssetType(request.getAssetType());
        asset.setCurrentPrice(request.getCurrentPrice());

        //save asset
        Asset updatedAsset =  assetRepository.save(asset);

        return new AssetResponse(updatedAsset.getSymbol(), updatedAsset.getName(),
                updatedAsset.getAssetType(), updatedAsset.getCurrentPrice(), updatedAsset.getAssetStatus());
    }

    public AssetResponse updateAssetStatus(Long assetId, UpdateAssetStatusRequest request) {
        //find asset by id
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        asset.setAssetStatus(request.getAssetStatus());

        //save asset
        Asset updatedAsset =  assetRepository.save(asset);

        return new AssetResponse(updatedAsset.getSymbol(), updatedAsset.getName(),
                updatedAsset.getAssetType(), updatedAsset.getCurrentPrice(), updatedAsset.getAssetStatus());
    }
}
