package com.ga.investmentservice.Repository;

import com.ga.investmentservice.Model.Portfolio;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {
    //method to lock database row for a portfolio
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Portfolio p WHERE p.id = :portfolioId")
    Optional<Portfolio> findByIdForUpdate(@Param("portfolioId") Long portfolioId);

    boolean existsByUserId(Long userId);

    Optional<Portfolio> findByUserId(Long userId);

    @Modifying
    @Query(value = """
        INSERT INTO portfolios
        (user_id, cash_balance, reserved_cash, created_at, updated_at)
        VALUES (:userId, 100000.00, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (user_id) DO NOTHING
        """, nativeQuery = true)
    int createInitialPortfolio(@Param("userId") Long userId);
}
