package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.CreateOrderRequest;
import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.DTO.Response.InvestmentOrderResponse;
import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Service.InvestmentOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "Investment Orders", description = "Create Buy/Sell order limit")
@SecurityRequirement(name = "bearerAuth")
public class InvestmentOrderController {
    private final InvestmentOrderService investmentOrderService;

    @PostMapping
    @Operation(summary = "Place a limit order", description = "Create a BUY or SELL limit order")
    public ResponseEntity<?> placeLimitOrder(@Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        String email = authentication.getName();
        investmentOrderService.placeLimitOrder(email, request);
        return ResponseEntity.ok("Limit order placed successfully");
    }

    @GetMapping
    public ResponseEntity<List<InvestmentOrderResponse>> viewOrders(Authentication authentication){
        String email = authentication.getName();
        List<InvestmentOrderResponse> orders = investmentOrderService.getMyOrders(email);
        return ResponseEntity.ok(orders);
    }
}
