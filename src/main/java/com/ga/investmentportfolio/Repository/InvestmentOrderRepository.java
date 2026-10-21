package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.Holding;
import com.ga.investmentportfolio.Model.InvestmentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InvestmentOrderRepository extends JpaRepository<InvestmentOrder, Long> {
}
