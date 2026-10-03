package com.ga.investmentportfolio.DTO.Response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class HoldingResponse {
    private Long holdingId;
    private String assetSymbol;
    private String assetName;
    private BigDecimal quantity;
    private BigDecimal averageBuyPrice;
    private BigDecimal currentPrice;
    private BigDecimal currentValue;
}
