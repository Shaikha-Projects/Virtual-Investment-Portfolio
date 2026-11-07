package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Enums.PriceAlertStatus;
import com.ga.investmentportfolio.Model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {
    //method to retrieve alerts belonging to the investor's portfolio
    List<PriceAlert> findByPortfolioOrderByCreatedAtDesc(Portfolio portfolio);

    //method to retrieve alerts for an asset with a specific status
    List<PriceAlert> findByAssetAndStatus(Asset asset, PriceAlertStatus status);

}
