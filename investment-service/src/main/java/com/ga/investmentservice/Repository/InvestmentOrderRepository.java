package com.ga.investmentservice.Repository;

import com.ga.investmentservice.Enums.OrderStatus;
import com.ga.investmentservice.Model.InvestmentOrder;
import com.ga.investmentservice.Model.Portfolio;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    List<InvestmentOrder> findByPortfolio_UserIdOrderByCreatedAtDesc(Portfolio portfolio);

    //method to retrieve orders by their status
    List<InvestmentOrder> findByStatus(OrderStatus status);

    //method to lock the database for an investment order
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM InvestmentOrder o WHERE o.id = :orderId")
    Optional<InvestmentOrder> findByIdForUpdate(@Param("orderId") Long orderId);

    Page<InvestmentOrder> findByPortfolioUserEmailAddressOrderByCreatedAtDesc(String email, Pageable pageable);
}
