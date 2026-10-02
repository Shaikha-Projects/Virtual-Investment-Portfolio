package com.ga.investmentportfolio.DTO.Request;

import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Enums.AssetType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class UpdateAssetStatusRequest {
    @NotNull(message = "Asset status is required")
    private AssetStatus assetStatus;
}
