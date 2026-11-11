package com.ga.investmentservice.Service;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Repository.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;


import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PortfolioProvisioningService {
    private final PortfolioRepository portfolioRepository;

    @Transactional
    public void createPortfolio(Long userId) {
        portfolioRepository.createInitialPortfolio(userId);
    }
}
