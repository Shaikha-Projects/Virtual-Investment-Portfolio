package com.ga.investmentservice.DTO.Response;

import com.ga.investmentservice.Enums.OrderStatus;
import com.ga.investmentservice.Enums.PriceAlertCondition;
import com.ga.investmentservice.Enums.PriceAlertStatus;
import com.ga.investmentservice.Enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class PriceAlertResponse {
    private Long id;
    private String assetSymbol;
    private String assetName;
    private BigDecimal targetPrice;
    private PriceAlertCondition condition;
    private PriceAlertStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime triggeredAt;
}
