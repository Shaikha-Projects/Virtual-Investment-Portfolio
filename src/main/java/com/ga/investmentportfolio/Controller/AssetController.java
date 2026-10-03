package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.BuyAssetRequest;
import com.ga.investmentportfolio.DTO.Request.SellAssetRequest;
import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.DTO.Response.TransactionResponse;
import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Service.AssetService;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetController {
    private final AssetService assetService;

    @GetMapping
    public List<AssetResponse> viewAssets(@RequestParam(required = false) String search,
                                          @RequestParam(required = false) AssetType type){
        return assetService.searchAssets(search, type);
    }


}
