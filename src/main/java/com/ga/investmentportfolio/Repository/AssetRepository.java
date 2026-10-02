package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetRepository extends JpaRepository<Asset, Long> {

    //find active assets
    List<Asset> findByAssetStatus(AssetStatus assetStatus);
}
