package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.Holding;
import com.ga.investmentportfolio.Model.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HoldingRepository extends JpaRepository<Holding, Long> {

    //when buying an asset, to check weather it's in holding to update or create a new holding
    Optional<Holding> findByPortfolioAndAsset(Portfolio portfolio, Asset asset);

    //to list all assets owned buy user
    List<Holding> findByPortfolio(Portfolio portfolio);

}
