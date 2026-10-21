package com.ga.investmentportfolio.DTO.Response;


import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class PriceHistoryResponse {
    private BigDecimal price;
    private LocalDateTime recordedAt;
}
