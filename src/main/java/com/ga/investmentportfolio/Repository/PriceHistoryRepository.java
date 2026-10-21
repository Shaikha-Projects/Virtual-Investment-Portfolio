package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
    //method to retrieve price history for an asset, ordered by time
    List<PriceHistory> findByAssetIdOrderByRecordedAtAsc(Long assetId);
}
