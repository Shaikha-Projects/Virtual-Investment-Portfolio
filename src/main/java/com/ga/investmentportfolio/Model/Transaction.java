package com.ga.investmentportfolio.Model;

import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Enums.TransactionStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString(exclude = {"portfolio", "asset"})
@Entity
@Table(name="transactions")
public class Transaction {
    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column (nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionType transactionType;

    @Column (nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column (nullable = false, precision = 15, scale = 2)
    private BigDecimal pricePerUnit;

    @Column (nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column (nullable = false)
    @Enumerated(EnumType.STRING)
    private TransactionStatus transactionStatus;

    @Column (nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;


    //single portfolio can contain many transactions
    //many transaction point to one portfolio
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", referencedColumnName = "id", nullable = false)
    private Portfolio portfolio;

    //many transactions can refer to the same asset
    //single transactions refer to one asset
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", referencedColumnName = "id", nullable = false)
    private Asset asset;

}
