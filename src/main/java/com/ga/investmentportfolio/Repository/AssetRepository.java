package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {

    //find active assets
    List<Asset> findByAssetStatus(AssetStatus assetStatus);

    //search active assets by symbol or name
    List<Asset> findByAssetStatusAndSymbolContainingIgnoreCaseOrAssetStatusAndNameContainingIgnoreCase(
            AssetStatus assetStatus1, String symbol,
            AssetStatus assetStatus2, String name
    );

    //search active assets by symbol or name within a specific asset type
    @Query("""
        SELECT a FROM Asset a WHERE a.assetStatus = :assetStatus
        AND a.assetType = :assetType
        AND (LOWER(a.symbol) LIKE LOWER(CONCAT('%', :search, '%'))
        OR LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    List<Asset> searchByType(AssetStatus assetStatus, AssetType assetType, String search);

    //filter active assets belonging to a specific asset type
    List<Asset> findByAssetStatusAndAssetType(AssetStatus assetStatus, AssetType assetType);

    //check duplicate asset symbols
    boolean existsBySymbolIgnoreCase(String symbol);
}
