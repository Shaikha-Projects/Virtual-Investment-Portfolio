package com.ga.investmentportfolio.DTO.Response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class WatchlistResponse {
    private Long watchlistId;
    private String assetSymbol;
    private String assetName;
    private BigDecimal currentPrice;
    private LocalDateTime createdAt;
}
