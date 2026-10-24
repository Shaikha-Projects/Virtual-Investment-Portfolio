package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Model.Holding;
import com.ga.investmentportfolio.Model.InvestmentOrder;
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
public interface InvestmentOrderRepository extends JpaRepository<InvestmentOrder, Long> {
    //method to retrieve orders belonging to the investor's portfolio
    List<InvestmentOrder> findByPortfolioOrderByCreatedAtDesc(Portfolio portfolio);

    //method to retrieve orders by their status
    List<InvestmentOrder> findByStatus(OrderStatus status);

    //method to lock the database for an investment order
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM InvestmentOrder o WHERE o.id = :orderId")
    Optional<InvestmentOrder> findByIdForUpdate(@Param("orderId") Long orderId);
}
