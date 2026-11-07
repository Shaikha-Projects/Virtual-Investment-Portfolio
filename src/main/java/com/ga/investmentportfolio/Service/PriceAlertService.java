package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.PriceAlertRequest;
import com.ga.investmentportfolio.DTO.Response.PriceAlertResponse;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Enums.PriceAlertCondition;
import com.ga.investmentportfolio.Enums.PriceAlertStatus;
import com.ga.investmentportfolio.Exception.BusinessRuleException;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.Portfolio;
import com.ga.investmentportfolio.Model.PriceAlert;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PriceAlertService {
    private final PriceAlertRepository priceAlertRepository;
    private final PortfolioRepository portfolioRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;


    //method to create a price alert
    @Transactional
    public void createAlert(String email, PriceAlertRequest request) {
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user portfolio
        Portfolio portfolio = user.getPortfolio();

        //find asset
        Asset asset = assetRepository.findByIdForUpdate(request.getAssetId())
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        //check asset status
        if(asset.getAssetStatus() != AssetStatus.ACTIVE){
            throw new BusinessRuleException("Asset is not available for trading");
        }

        BigDecimal targetPrice = request.getTargetPrice();
        BigDecimal currentPrice = asset.getCurrentPrice();

        //validate ABOVE condition
        if(request.getCondition() == PriceAlertCondition.ABOVE){
            if (targetPrice.compareTo(currentPrice) <= 0) {
                throw new BusinessRuleException("Target price must be higher than current price");
            }
        }
        //validate BELOW condition
        if (request.getCondition() == PriceAlertCondition.BELOW) {
            if (targetPrice.compareTo(currentPrice) >= 0) {
                throw new BusinessRuleException("Target price must be lower than current price");
            }
        }

        // create a PriceAlert object
        PriceAlert priceAlert = new PriceAlert();

        //Set its portfolio, asset, target price, condition, and ACTIVE status.
        priceAlert.setAsset(asset);
        priceAlert.setPortfolio(portfolio);
        priceAlert.setTargetPrice(targetPrice);
        priceAlert.setCondition(request.getCondition());
        priceAlert.setStatus(PriceAlertStatus.ACTIVE);

        //save it
        priceAlertRepository.save(priceAlert);

    }

    //method to get all alerts
    @Transactional
    public List<PriceAlertResponse> getAlerts(String email){
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user portfolio
        Portfolio portfolio = user.getPortfolio();

        //get user alerts
        List<PriceAlert> alerts = priceAlertRepository.findByPortfolioOrderByCreatedAtDesc(portfolio);

        //convert into response dto
        List<PriceAlertResponse> responses = new ArrayList<>();

        for (PriceAlert alert : alerts) {
            //create a PriceAlertRespon
            PriceAlertResponse response = new PriceAlertResponse(
                    alert.getId(), alert.getAsset().getSymbol(), alert.getAsset().getName(),
                    alert.getTargetPrice(), alert.getCondition(), alert.getStatus(),
                    alert.getCreatedAt(), alert.getTriggeredAt()
            );
            //add the response to the responses list
            responses.add(response);
        }

        return responses;
    }
}
