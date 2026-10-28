package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Response.PortfolioSnapshotResponse;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Model.Holding;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.PortfolioSnapshot;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.HoldingRepository;
import com.ga.investmentportfolio.Repository.PortfolioRepository;
import com.ga.investmentportfolio.Repository.PortfolioSnapshotRepository;
import com.ga.investmentportfolio.Repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PortfolioSnapshotService {
    private final PortfolioRepository portfolioRepository;
    private final HoldingRepository holdingRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final UserRepository userRepository;

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
    public List<PortfolioSnapshotResponse> getPortfolioHistory(String email) {

        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get the user's portfolio
        Portfolio portfolio = user.getPortfolio();

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

        return responses;
    }
}
