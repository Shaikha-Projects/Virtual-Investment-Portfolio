package com.ga.investmentservice.Repository;

import com.ga.investmentservice.Enums.PriceAlertStatus;
import com.ga.investmentservice.Model.Asset;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.PriceAlert;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {
    //method to retrieve alerts belonging to the investor's portfolio
    List<PriceAlert> findByPortfolioOrderByCreatedAtDesc(Portfolio portfolio);

    //method to retrieve alerts for an asset with a specific status
    List<PriceAlert> findByAssetAndStatusOrderByIdAsc(Asset asset, PriceAlertStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PriceAlert p WHERE p.id = :id")
    Optional<PriceAlert> findByIdForUpdate(@Param("id") Long id);

}
