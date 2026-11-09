package com.ga.investmentservice.Repository;

import com.ga.investmentservice.Model.Asset;
import com.ga.investmentservice.Model.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    boolean existsByUserAndAsset(Long userId, Asset asset);

    List<Watchlist> findByUser(Long userId);

    //find by watchlist record id and user
    Optional<Watchlist> findByIdAndUser(Long id, Long userId);

}
