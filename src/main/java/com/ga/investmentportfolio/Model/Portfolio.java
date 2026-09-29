package com.ga.investmentportfolio.Model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
@ToString(exclude = {"user", "holdings", "transactions"})
@Entity
@Table(name="portfolios")
public class Portfolio {

    @Id
    @Column
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column (nullable = false, precision = 15, scale = 2)
    private BigDecimal cashBalance;

    @Column (nullable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    //one user have one portfolio
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false, unique = true)
    private User user;

    //one portfolio can contain many holdings
    @OneToMany(mappedBy = "portfolio", fetch = FetchType.LAZY)
    private List<Holding> holdings;

    //one portfolio can contain many transactions
    @OneToMany(mappedBy = "portfolio", fetch = FetchType.LAZY)
    private List<Transaction> transactions;


}
