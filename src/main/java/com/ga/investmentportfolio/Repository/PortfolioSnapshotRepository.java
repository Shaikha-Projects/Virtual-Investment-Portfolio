package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.PortfolioSnapshot;
import com.ga.investmentportfolio.Model.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, Long> {

    //get portfolio snapshots ordered by date
    List<PortfolioSnapshot> findByPortfolioOrderByRecordedAtAsc(Portfolio portfolio);

}
