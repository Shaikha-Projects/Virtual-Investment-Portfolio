package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.BuyAssetRequest;
import com.ga.investmentportfolio.DTO.Request.SellAssetRequest;
import com.ga.investmentportfolio.DTO.Response.HoldingResponse;
import com.ga.investmentportfolio.DTO.Response.PortfolioPerformanceResponse;
import com.ga.investmentportfolio.DTO.Response.TransactionHistoryResponse;
import com.ga.investmentportfolio.DTO.Response.TransactionResponse;
import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Service.AssetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springdoc.core.annotations.ParameterObject;

import java.util.List;

@RestController
@RequestMapping("/portfolio")
@AllArgsConstructor
@Tag(name = "Portfolio", description = "Manage investments, holdings, transactions and portfolio performance")
@SecurityRequirement(name = "bearerAuth")
public class PortfolioController {
    private final AssetService assetService;

    @Operation(
            summary = "Buy asset",
            description = "Purchases a quantity of an active asset using the authenticated user's virtual cash balance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asset purchased successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid quantity, inactive asset, or insufficient cash balance"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User, portfolio, or asset does not exist")})
    @PostMapping("/buy")
    public TransactionResponse buyAsset(@Valid @RequestBody BuyAssetRequest request,
                                        Authentication authentication){

        String email = authentication.getName();
        return assetService.buyAsset(email, request);

    }

    @Operation(
            summary = "View holdings",
            description = "Returns authenticated user's current investment holdings and their current values.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Holdings retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User or portfolio does not exist")})
    @GetMapping("/holdings")
    public List<HoldingResponse> getHoldings(Authentication authentication){
        String email = authentication.getName();
        return assetService.getHoldings(email);
    }

    @Operation(
            summary = "Sell asset",
            description = "Sells a quantity of an asset currently held by the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asset sold successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid quantity or insufficient quantity owned"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User, portfolio, asset, or holding does not exist")})
    @PostMapping("/sell")
    public TransactionResponse sellAsset(@Valid @RequestBody SellAssetRequest request, Authentication authentication) {

        // get authenticated user's email
        String emailAddress = authentication.getName();

        return assetService.sellAsset(emailAddress, request);
    }

    @Operation(
            summary = "View transaction history",
            description = "Returns the authenticated user's transaction history. Results can optionally be filtered by transaction type and asset symbol.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction history retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User or portfolio does not exist")})
    @GetMapping("/transactions")
    public Page<TransactionHistoryResponse> getTransactionHistory(@RequestParam(required = false) TransactionType type,
                                                                  @RequestParam(required = false) String symbol,
                                                                  Authentication authentication,
                                                                  @ParameterObject
                                                                      @PageableDefault(
                                                                          size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
                                                                      Pageable pageable) {
        // get authenticated user's email
        String emailAddress = authentication.getName();
        return assetService.getTransactionHistory(emailAddress, type, symbol, pageable);
    }

    @Operation(
            summary = "View portfolio performance",
            description = "Returns the authenticated user's cash balance, holdings value, total portfolio value, cost basis and unrealized gain or loss.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Portfolio performance retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User or portfolio does not exist")})
    @GetMapping("/performance")
    public PortfolioPerformanceResponse getPortfolioPerformance(Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();

        return assetService.getPortfolioPerformance(emailAddress);
    }


}
