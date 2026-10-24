package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.Holding;
import com.ga.investmentportfolio.Model.Portfolio;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HoldingRepository extends JpaRepository<Holding, Long> {

    //method to lock database row for a holding
    //when buying an asset, to check weather it's in holding to update or create a new holding
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM Holding h WHERE h.portfolio = :portfolio AND h.asset = :asset")
    Optional<Holding> findByPortfolioAndAsset(@Param("portfolio") Portfolio portfolio, @Param("asset") Asset asset);

    //to list all assets owned buy user
    List<Holding> findByPortfolio(Portfolio portfolio);

}
