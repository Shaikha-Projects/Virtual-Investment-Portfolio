package com.ga.investmentportfolio.Model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString(exclude = {"user", "asset"})
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

    //one user have many watchlist
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false)
    private User user;

    //many Watchlist can refer to the same asset
    //single Watchlist refer to one asset
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", referencedColumnName = "id", nullable = false)
    private Asset asset;

}
