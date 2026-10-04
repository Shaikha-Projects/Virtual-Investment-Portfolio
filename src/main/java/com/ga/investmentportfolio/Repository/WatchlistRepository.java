package com.ga.investmentportfolio.Repository;

import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Model.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    boolean existsByUserAndAsset(User user, Asset asset);

}
