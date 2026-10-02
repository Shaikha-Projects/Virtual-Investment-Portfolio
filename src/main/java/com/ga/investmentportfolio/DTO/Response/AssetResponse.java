package com.ga.investmentportfolio.DTO.Response;

import com.ga.investmentportfolio.Enums.AssetType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class AssetResponse {
    private String symbol;
    private String name;
    private AssetType assetType;
    private BigDecimal currentPrice;
}
