package com.ga.investmentportfolio.DTO.Response;

import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Enums.PriceAlertCondition;
import com.ga.investmentportfolio.Enums.PriceAlertStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
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
