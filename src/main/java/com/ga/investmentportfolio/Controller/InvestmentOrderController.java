package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.CreateOrderRequest;
import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.DTO.Response.InvestmentOrderResponse;
import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Service.InvestmentOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Investment Orders", description = "Place, view, and cancel BUY and SELL limit orders")
@SecurityRequirement(name = "bearerAuth")
public class InvestmentOrderController {
    private final InvestmentOrderService investmentOrderService;

    @PostMapping
    @Operation(summary = "Place a limit order", description = "Create a BUY or SELL limit order")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Limit order placed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid quantity, limit price, insufficient funds, or insufficient shares"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Portfolio or asset not found"),
            @ApiResponse(responseCode = "429", description = "Too many requests")
    })
    public ResponseEntity<?> placeLimitOrder(@Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        String email = authentication.getName();
        investmentOrderService.placeLimitOrder(email, request);
        return ResponseEntity.ok("Limit order placed successfully");
    }

    @GetMapping
    @Operation(summary = "View all investment orders")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Investment orders retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User or portfolio not found")
    })
    public ResponseEntity<Page<InvestmentOrderResponse>> viewOrders(Authentication authentication,
                                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                                    @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size){
        String email = authentication.getName();
        Page<InvestmentOrderResponse> orders = investmentOrderService.getMyOrders(email, page, size);
        return ResponseEntity.ok(orders);
    }

    @PatchMapping("/{id}/cancel")
    @Operation(summary = "Cancel a pending investment order")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Limit order cancelled successfully"),
            @ApiResponse(responseCode = "400", description = "Order cannot be cancelled because it is no longer pending"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "User is not authorized to cancel this order"),
            @ApiResponse(responseCode = "404", description = "Investment order not found"),
            @ApiResponse(responseCode = "429", description = "Too many requests")
    })
    public ResponseEntity<String> cancelOrder(@PathVariable Long id, Authentication authentication) {
        String email = authentication.getName();
        investmentOrderService.cancelOrder(email, id);
        return ResponseEntity.ok("Order is successfully cancelled");
    }


}
