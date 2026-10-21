package com.ga.investmentportfolio.DTO.Response;


import com.ga.investmentportfolio.Enums.OrderStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class InvestmentOrderResponse {
    private Long id;
    private Long assetId;
    private TransactionType orderType;
    private BigDecimal quantity;
    private BigDecimal limitPrice;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime executedAt;
}
