package com.ga.investmentportfolio.Model;

import com.ga.investmentportfolio.Enums.PriceAlertCondition;
import com.ga.investmentportfolio.Enums.PriceAlertStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "price_alert")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PriceAlert {

    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column (nullable = false, precision = 15, scale = 2)
    private BigDecimal targetPrice;

    @Column (nullable = false)
    @Enumerated(EnumType.STRING)
    private PriceAlertCondition condition;

    @Column (nullable = false)
    @Enumerated(EnumType.STRING)
    private PriceAlertStatus status;

    @Column (nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime triggeredAt;

    //single portfolio can contain many alerts
    //many alert point to one portfolio
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", referencedColumnName = "id", nullable = false)
    private Portfolio portfolio;

    //many alerts can refer to the same asset
    //single alert refer to one asset
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", referencedColumnName = "id", nullable = false)
    private Asset asset;

}
