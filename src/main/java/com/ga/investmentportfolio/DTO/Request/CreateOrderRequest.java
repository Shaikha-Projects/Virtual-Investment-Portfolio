package com.ga.investmentportfolio.DTO.Request;

import com.ga.investmentportfolio.Enums.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class CreateOrderRequest {

    @NotNull(message = "Asset id is required")
    private Long assetId;

    @NotNull(message = "Order type is required")
    private TransactionType orderType;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.01", message = "Quantity must be greater than 0")
    private BigDecimal quantity;

    @NotNull(message = "Limit price is required")
    @DecimalMin(value = "0.01", message = "Limit price must be greater than 0")
    private BigDecimal limitPrice;

}
