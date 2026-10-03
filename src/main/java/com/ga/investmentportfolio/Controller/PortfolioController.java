package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.BuyAssetRequest;
import com.ga.investmentportfolio.DTO.Response.HoldingResponse;
import com.ga.investmentportfolio.DTO.Response.TransactionResponse;
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


}
