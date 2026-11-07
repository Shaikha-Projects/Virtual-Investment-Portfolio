package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.PriceAlertRequest;
import com.ga.investmentportfolio.DTO.Response.HoldingResponse;
import com.ga.investmentportfolio.DTO.Response.PriceAlertResponse;
import com.ga.investmentportfolio.Service.PriceAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/price-alerts")
@RequiredArgsConstructor
@Tag(name = "Price Alert", description = "view alerts, create price alert, cancel price alert")
public class PriceAlertController {
    private final PriceAlertService priceAlertService;

    @Operation(
            summary = "View alerts",
            description = "Returns authenticated user's current price alerts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alerts retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User or portfolio does not exist")})
    @GetMapping
    public List<PriceAlertResponse> getAlerts(Authentication authentication){
        String email = authentication.getName();
        return priceAlertService.getAlerts(email);
    }

    @PostMapping
    @Operation(
            summary = "Create price alert",
            description = "Creates a price alert for the authenticated investor."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Price alert created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid price alert request"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Asset does not exist")
    })
    public ResponseEntity<Void> createAlert(Authentication authentication,
                                            @Valid @RequestBody PriceAlertRequest request) {

        String email = authentication.getName();
        priceAlertService.createAlert(email, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PatchMapping("/{id}/cancel")
    @Operation(
            summary = "Cancel price alert",
            description = "Cancels an active price alert belonging to the authenticated investor."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Price alert cancelled successfully"),
            @ApiResponse(responseCode = "400", description = "Alert cannot be cancelled"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "Price alert does not exist")
    })
    public ResponseEntity<Void> cancelAlert(Authentication authentication, @PathVariable Long id) {
        String email = authentication.getName();
        priceAlertService.cancelAlert(email, id);
        return ResponseEntity.noContent().build();
    }
}
