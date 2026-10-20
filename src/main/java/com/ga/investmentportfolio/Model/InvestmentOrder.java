package com.ga.investmentportfolio.Model;

import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name="investment_orders")
public class InvestmentOrder {

    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column (nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionType orderType;

    @Column (nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column (nullable = false, precision = 15, scale = 2)
    private BigDecimal limitPrice;

    @Column (nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column (nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime executedAt;

    //single portfolio can contain many orders
    //many orders point to one portfolio
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", referencedColumnName = "id", nullable = false)
    private Portfolio portfolio;

    //many orders can refer to the same asset
    //single order refer to one asset
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", referencedColumnName = "id", nullable = false)
    private Asset asset;

}
