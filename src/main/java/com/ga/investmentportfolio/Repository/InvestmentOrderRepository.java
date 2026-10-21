package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Model.Holding;
import com.ga.investmentportfolio.Model.InvestmentOrder;
import com.ga.investmentportfolio.Model.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvestmentOrderRepository extends JpaRepository<InvestmentOrder, Long> {
    //method to retrieve orders belonging to the investor's portfolio
    List<InvestmentOrder> findByPortfolioOrderByCreatedAtDesc(Portfolio portfolio);

    //method to retrieve orders by their status
    List<InvestmentOrder> findByStatus(OrderStatus status);
}
