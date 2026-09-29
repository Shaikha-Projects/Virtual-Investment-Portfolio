package com.ga.investmentportfolio.Model;

import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Enums.AssetType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString(exclude = {"holdings", "transactions", "watchlists"})
@Entity
@Table(name="assets")
public class Asset {
    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column (nullable = false, unique = true)
    private String symbol;

    @Column (nullable = false)
    private String name;

    @Column (nullable = false)
    @Enumerated(EnumType.STRING)
    private AssetType assetType;

    @Column (nullable = false, precision = 15, scale = 2)
    private BigDecimal currentPrice;

    @Column (nullable = false)
    @Enumerated(EnumType.STRING)
    private AssetStatus assetStatus;

    @Column (nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    //one asset can refer many holdings
    @OneToMany(mappedBy = "asset", fetch = FetchType.LAZY)
    private List<Holding> holdings;

    //one asset can contain many transactions
    @OneToMany(mappedBy = "asset", fetch = FetchType.LAZY)
    private List<Transaction> transactions;

    //one asset can appear im many watchlists
    @OneToMany(mappedBy = "asset", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Watchlist> watchlists;

}
