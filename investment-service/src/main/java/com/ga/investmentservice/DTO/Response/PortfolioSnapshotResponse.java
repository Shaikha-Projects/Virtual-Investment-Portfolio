package com.ga.investmentservice.DTO.Response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class PortfolioSnapshotResponse {
    private BigDecimal totalPortfolioValue;
    private LocalDateTime recordedAt;
}
