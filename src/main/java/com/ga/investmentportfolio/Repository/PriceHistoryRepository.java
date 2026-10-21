package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
}
