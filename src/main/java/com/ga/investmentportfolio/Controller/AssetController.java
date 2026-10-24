package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.DTO.Response.PriceHistoryResponse;
import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Service.AssetService;
import com.ga.investmentportfolio.Service.MarketSimulationService;
import lombok.RequiredArgsConstructor;
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
    private final MarketSimulationService marketSimulationService;

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

    @Operation(summary = "Get asset price history")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Price history retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required")})
    @GetMapping("/{assetId}/price-history")
    public List<PriceHistoryResponse> getPriceHistory(@PathVariable Long assetId) {
        return marketSimulationService.getPriceHistory(assetId);
    }


}
