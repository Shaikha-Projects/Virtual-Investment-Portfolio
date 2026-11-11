package com.ga.investmentservice.Repository;

import com.ga.investmentservice.Model.Asset;
import com.ga.investmentservice.Model.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    boolean existsByUserIdAndAsset(Long userId, Asset asset);

    List<Watchlist> findByUserId(Long userId);

    //find by watchlist record id and user
    Optional<Watchlist> findByIdAndUserId(Long id, Long userId);

}
