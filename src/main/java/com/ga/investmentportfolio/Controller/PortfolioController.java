package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.BuyAssetRequest;
import com.ga.investmentportfolio.DTO.Request.SellAssetRequest;
import com.ga.investmentportfolio.DTO.Response.HoldingResponse;
import com.ga.investmentportfolio.DTO.Response.PortfolioPerformanceResponse;
import com.ga.investmentportfolio.DTO.Response.TransactionHistoryResponse;
import com.ga.investmentportfolio.DTO.Response.TransactionResponse;
import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Service.AssetService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/portfolio")
@AllArgsConstructor
public class PortfolioController {
    private final AssetService assetService;

    @PostMapping("/buy")
    public TransactionResponse buyAsset(@Valid @RequestBody BuyAssetRequest request,
                                        Authentication authentication){

        String email = authentication.getName();
        return assetService.buyAsset(email, request);

    }

    @GetMapping("/holdings")
    public List<HoldingResponse> getHoldings(Authentication authentication){
        String email = authentication.getName();
        return assetService.getHoldings(email);
    }

    @PostMapping("/sell")
    public TransactionResponse sellAsset(@Valid @RequestBody SellAssetRequest request, Authentication authentication) {

        // get authenticated user's email
        String emailAddress = authentication.getName();

        return assetService.sellAsset(emailAddress, request);
    }

    @GetMapping("transactions")
    public List<TransactionHistoryResponse> getTransactionHistory(@RequestParam(required = false) TransactionType type,
                                                                  @RequestParam(required = false) String symbol,
                                                                  Authentication authentication) {
        // get authenticated user's email
        String emailAddress = authentication.getName();

        return assetService.getTransactionHistory(emailAddress, type, symbol);

    }

    @GetMapping("/performance")
    public PortfolioPerformanceResponse getPortfolioPerformance(Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();

        return assetService.getPortfolioPerformance(emailAddress);
    }


}
