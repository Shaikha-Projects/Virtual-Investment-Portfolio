package com.ga.investmentservice.DTO.Response;

import com.ga.investmentservice.Enums.TransactionStatus;
import com.ga.investmentservice.Enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class TransactionResponse {
    private Long transactionId;
    private TransactionType transactionType;
    private String assetSymbol;
    private BigDecimal quantity;
    private BigDecimal pricePerUnit;
    private BigDecimal totalAmount;
    private TransactionStatus transactionStatus;
    private LocalDateTime createdAt;
    private BigDecimal cashBalance;
}
