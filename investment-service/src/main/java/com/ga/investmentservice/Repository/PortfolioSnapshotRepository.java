package com.ga.investmentservice.Repository;

import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.PortfolioSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, Long> {

    //get portfolio snapshots ordered by date
    List<PortfolioSnapshot> findByPortfolioOrderByRecordedAtAsc(Portfolio portfolio);

}
