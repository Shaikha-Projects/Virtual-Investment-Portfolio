package com.ga.investmentservice.Model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString(exclude = {"asset"})
@Entity
@Table(name="watchlist",
        uniqueConstraints = @UniqueConstraint(name = "user_asset", columnNames = {"user_id", "asset_id"}))
public class Watchlist {

    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column (nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    //many Watchlist can refer to the same asset
    //single Watchlist refer to one asset
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", referencedColumnName = "id", nullable = false)
    private Asset asset;

}
