package com.ga.investmentservice.Service;

import com.ga.investmentservice.DTO.Request.PriceAlertRequest;
import com.ga.investmentservice.DTO.Response.PriceAlertResponse;
import com.ga.investmentservice.Enums.AssetStatus;
import com.ga.investmentservice.Enums.PriceAlertCondition;
import com.ga.investmentservice.Enums.PriceAlertStatus;
import com.ga.investmentservice.Event.PriceAlertTriggeredEvent;
import com.ga.investmentservice.Exception.BusinessRuleException;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import com.ga.investmentservice.Model.Asset;
import com.ga.investmentservice.Model.Portfolio;
import com.ga.investmentservice.Model.PriceAlert;
import com.ga.investmentservice.Repository.*;
import com.ga.investmentservice.Security.CurrentUser;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PriceAlertService {
    private final PriceAlertRepository priceAlertRepository;
    private final PortfolioRepository portfolioRepository;
    private final AssetRepository assetRepository;
    private final CurrentUser currentUser;
    private final InvestmentEventService investmentEventService;


    //method to create a price alert
    @Transactional
    @PreAuthorize("hasRole('USER')")
    public void createAlert(String email, PriceAlertRequest request) {
        //find user by email
        Long userId = currentUser.getUserId(email);

        //get user portfolio
        Portfolio portfolio = portfolioRepository.findByUserId(userId)
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));

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
    @PreAuthorize("hasRole('USER')")
    public List<PriceAlertResponse> getAlerts(String email){
        //find user by email
        Long userId = currentUser.getUserId(email);

        //get user portfolio
        Portfolio portfolio = portfolioRepository.findByUserId(userId)
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));

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

    //method to cancel alert
    @Transactional
    @PreAuthorize("hasRole('USER')")
    public void cancelAlert(String email, Long alertId) {
        //find user by email
        Long userId = currentUser.getUserId(email);

        //get user portfolio
        Portfolio portfolio = portfolioRepository.findByUserId(userId)
                .orElseThrow(() -> new InformationNotFoundException("Portfolio does not exist"));

        //retrieve alert, lock price alerts bt id
        PriceAlert alert = priceAlertRepository.findByIdForUpdate(alertId)
                .orElseThrow(() -> new InformationNotFoundException("Price alert does not exist"));

        //check portfolio ownership
        if (!alert.getPortfolio().getId().equals(portfolio.getId())) {
            throw new BusinessRuleException("You cannot cancel another investor's alert");
        }

        //validate alert status
        if (alert.getStatus() != PriceAlertStatus.ACTIVE) {
            throw new BusinessRuleException("Only active alerts can be cancelled");
        }

        //update status
        alert.setStatus(PriceAlertStatus.CANCELLED);
    }

    //method to check price alerts
    @Transactional
    public void checkAlertsForAsset(Asset asset, BigDecimal newPrice) {
        //retrieve active alerts for this asset
        List<PriceAlert> alerts = priceAlertRepository.findByAssetAndStatusOrderByIdAsc(asset, PriceAlertStatus.ACTIVE);

        //loop
        for (PriceAlert alert : alerts) {

            //define trigger conditions above & below
            boolean aboveReached = alert.getCondition() == PriceAlertCondition.ABOVE && newPrice.compareTo(alert.getTargetPrice()) >= 0;
            boolean belowReached = alert.getCondition() == PriceAlertCondition.BELOW && newPrice.compareTo(alert.getTargetPrice()) <= 0;

            // Check whether the target price has been reached
            if (aboveReached || belowReached) {
                //trigger the alert safely

                //lock alert
                PriceAlert lockedAlert = priceAlertRepository.findByIdForUpdate(alert.getId())
                        .orElseThrow(() -> new InformationNotFoundException("Price alert does not exist"));

                //check its status again
                if (lockedAlert.getStatus() != PriceAlertStatus.ACTIVE) {
                    continue;
                }

                //mark alert triggered
                lockedAlert.setStatus(PriceAlertStatus.TRIGGERED);
                lockedAlert.setTriggeredAt(LocalDateTime.now());

                //publish notification
                investmentEventService.savePriceAlertEvent(
                        new PriceAlertTriggeredEvent(
                                portfolioRepository.findUserEmailByUserId(lockedAlert.getPortfolio().getUserId())
                                        .orElseThrow(() -> new InformationNotFoundException("User does not exist")),
                                "Price alert triggered: " + asset.getSymbol()
                                        + " reached $" + newPrice
                                        + " (target: $" + lockedAlert.getTargetPrice() + ")"
                        )
                );

            }



        }

    }
}
