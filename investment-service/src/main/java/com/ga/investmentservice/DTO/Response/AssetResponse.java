package com.ga.investmentservice.DTO.Response;

import com.ga.investmentservice.Enums.AssetStatus;
import com.ga.investmentservice.Enums.AssetType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class AssetResponse {
    private Long assetId;
    private String symbol;
    private String name;
    private AssetType assetType;
    private BigDecimal currentPrice;
    private AssetStatus assetStatus;
}
