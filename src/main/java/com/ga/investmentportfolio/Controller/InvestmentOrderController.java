package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.CreateOrderRequest;
import com.ga.investmentportfolio.Service.InvestmentOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
