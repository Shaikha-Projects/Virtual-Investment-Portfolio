package com.ga.investmentservice.DTO.Request;

import com.ga.investmentservice.Enums.AssetStatus;
import com.ga.investmentservice.Enums.AssetType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class UpdateAssetStatusRequest {
    @NotNull(message = "Asset status is required")
    private AssetStatus assetStatus;
}
