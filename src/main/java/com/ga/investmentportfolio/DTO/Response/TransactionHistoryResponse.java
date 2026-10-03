package com.ga.investmentportfolio.DTO.Response;

import com.ga.investmentportfolio.Enums.TransactionStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class TransactionHistoryResponse {
    private Long transactionId;
    private TransactionType transactionType;
    private String assetSymbol;
    private BigDecimal quantity;
    private BigDecimal pricePerUnit;
    private BigDecimal totalAmount;
    private TransactionStatus transactionStatus;
    private LocalDateTime createdAt;
}
