package com.ga.investmentservice.Service;

import com.ga.investmentservice.DTO.Response.PortfolioSnapshotResponse;
import com.ga.investmentservice.Config.CacheConfiguration;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import com.ga.investmentservice.Model.Holding;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.PortfolioSnapshot;
import com.ga.investmentservice.Repository.HoldingRepository;
import com.ga.investmentservice.Repository.PortfolioRepository;
import com.ga.investmentservice.Repository.PortfolioSnapshotRepository;
import com.ga.investmentservice.Security.CurrentUser;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PortfolioSnapshotService {
    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final CurrentUser currentUser;
    private final CacheManager cacheManager;

    //calculate and saves portfolio's value
    @Transactional
    public void saveSnapshot(Portfolio portfolio) {
        //get all holdings for this portfolio
        List<Holding> holdings = holdingRepository.findByPortfolio(portfolio);

        //calculate current holdings value
        BigDecimal holdingsValue = BigDecimal.ZERO;

        for (Holding holding : holdings) {
            BigDecimal currentValue = holding.getQuantity().multiply(holding.getAsset().getCurrentPrice());

            holdingsValue = holdingsValue.add(currentValue);
        }

        //calculate total portfolio value
        BigDecimal totalPortfolioValue = portfolio.getCashBalance().add(holdingsValue).setScale(2, RoundingMode.HALF_UP);

        //create portfolio snapshot
        PortfolioSnapshot snapshot = new PortfolioSnapshot();

        snapshot.setPortfolio(portfolio);
        snapshot.setTotalPortfolioValue(totalPortfolioValue);

        //save snapshot
        portfolioSnapshotRepository.save(snapshot);
        // Register explicitly: scheduled batches call this method within the same bean.
        afterCommit(() -> historyCache().evict(portfolio.getUserId()));
    }

    //save portfolio performance history every 5 minutes
    @Transactional
    @Scheduled(fixedDelay = 300000)
    public void saveAllSnapshots() {

        //get all portfolios
        List<Portfolio> portfolios = portfolioRepository.findAll();

        //save a snapshot for each portfolio
        for (Portfolio portfolio : portfolios) {
            saveSnapshot(portfolio);
        }
    }

    //get portfolio performance history for an investor
    @Transactional
    @PreAuthorize("hasRole('USER')")
    public List<PortfolioSnapshotResponse> getPortfolioHistory(String email) {

        //find user by email
        Long userId = currentUser.getUserId(email);

        //get the user's portfolio
        Portfolio portfolio = portfolioRepository.findByUserId(userId)
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));

        // Identity and portfolio validation must also execute on cache hits.
        Cache cache = historyCache();
        @SuppressWarnings("unchecked")
        List<PortfolioSnapshotResponse> cached = cache.get(userId, List.class);
        if (cached != null) {
            return cached;
        }

        //get saved snapshots ordered by date
        List<PortfolioSnapshot> snapshots = portfolioSnapshotRepository.findByPortfolioOrderByRecordedAtAsc(portfolio);

        //convert snapshots into response objects
        List<PortfolioSnapshotResponse> responses = new ArrayList<>();

        for (PortfolioSnapshot snapshot : snapshots) {
            PortfolioSnapshotResponse response = new PortfolioSnapshotResponse(
                    snapshot.getTotalPortfolioValue(),
                    snapshot.getRecordedAt()
            );

            responses.add(response);
        }

        List<PortfolioSnapshotResponse> history = List.copyOf(responses);
        // A read may join a snapshot-writing transaction. Never publish rolled-back data.
        afterCommit(() -> cache.put(userId, history));
        return history;
    }

    private Cache historyCache() {
        return Objects.requireNonNull(cacheManager.getCache(CacheConfiguration.PORTFOLIO_HISTORY));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
