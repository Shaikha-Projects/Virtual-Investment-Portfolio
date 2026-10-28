package com.ga.investmentportfolio.Model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "portfolio_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class PortfolioSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //many snapshots belong to one portfolio
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    //total portfolio value at the time of the snapshot
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalPortfolioValue;

    //when the snapshot was recorded
    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime recordedAt;
}
