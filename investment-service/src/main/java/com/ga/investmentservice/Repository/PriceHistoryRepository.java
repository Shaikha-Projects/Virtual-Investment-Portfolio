package com.ga.investmentservice.Repository;

import com.ga.investmentservice.Model.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
    //method to retrieve price history for an asset, ordered by time
    List<PriceHistory> findByAssetIdOrderByRecordedAtAsc(Long assetId);
}
