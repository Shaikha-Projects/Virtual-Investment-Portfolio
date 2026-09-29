package com.ga.investmentportfolio.Model;

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
@ToString(exclude = {"portfolio", "asset"})
@Entity
@Table(name="holdings",
        uniqueConstraints = @UniqueConstraint(name = "portfolio_asset", columnNames = {"portfolio_id", "asset_id"}))
public class Holding {
    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column (nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column (nullable = false, precision = 15, scale = 2)
    private BigDecimal averageBuyPrice;

    @Column (nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    //single portfolio can contain many holdings
    //many holding point to one portfolio
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", referencedColumnName = "id", nullable = false)
    private Portfolio portfolio;

    //many holding can refer to the same asset
    //single hodling refer to one asset
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", referencedColumnName = "id", nullable = false)
    private Asset asset;


}
