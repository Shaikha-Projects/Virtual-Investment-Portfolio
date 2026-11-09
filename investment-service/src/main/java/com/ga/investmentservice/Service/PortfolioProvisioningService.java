package com.ga.investmentservice.Service;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Repository.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PortfolioProvisioningService {
    private final PortfolioRepository portfolioRepository;

    @Transactional
    public void createPortfolio(Long userId) {
        //check if portfolio exists
        if (portfolioRepository.existsByUserId(userId)) {
            return;
        }

        //create portfolio
        Portfolio portfolio = new Portfolio();

        portfolio.setUserId(userId);
        portfolio.setCashBalance(new BigDecimal("100000.00"));
        portfolio.setReservedCash(BigDecimal.ZERO);

        portfolioRepository.save(portfolio);
    }
}
