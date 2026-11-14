package com.ga.investmentservice.DTO.Request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class DepositRequest {

    @NotNull(message = "Deposit amount is required")
    @DecimalMin(value = "0.01", message = "Deposit amount must be greater than 0")
    @Digits(integer = 13, fraction = 2, message = "Deposit amount must have at most two decimal places")
    private BigDecimal amount;
}
