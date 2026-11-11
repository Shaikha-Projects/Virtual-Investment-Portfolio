package com.ga.investmentservice.DTO.Request;

import com.ga.investmentservice.Enums.AssetStatus;
import com.ga.investmentservice.Enums.PriceAlertCondition;
import com.ga.investmentservice.Enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class PriceAlertRequest {

    @NotNull(message = "Asset id is required")
    private Long assetId;

    @NotNull(message = "Target price is required")
    @DecimalMin(value = "0.01", message = "Target price must be greater than 0")
    private BigDecimal targetPrice;

    @NotNull(message = "Price alert condition is required")
    private PriceAlertCondition condition;

}
