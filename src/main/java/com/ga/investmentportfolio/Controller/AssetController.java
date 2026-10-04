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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
@Tag(name = "Assets", description = "Browse and search active investment assets")
@SecurityRequirement(name = "bearerAuth")
public class AssetController {
    private final AssetService assetService;

    @Operation(
            summary = "View and search assets",
            description = "Returns active assets. Results can optionally be filtered by search text and asset type.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assets retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required")})
    @GetMapping
    public List<AssetResponse> viewAssets(@RequestParam(required = false) String search,
                                          @RequestParam(required = false) AssetType type){
        return assetService.searchAssets(search, type);
    }


}
