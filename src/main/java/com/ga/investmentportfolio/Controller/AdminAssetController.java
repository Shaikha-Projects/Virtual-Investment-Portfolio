package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.CreateAssetRequest;
import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.Service.AssetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/assets")
@RequiredArgsConstructor
public class AdminAssetController {
    private final AssetService assetService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AssetResponse createAsset(@Valid @RequestBody CreateAssetRequest assetRequest){
        return assetService.createAsset(assetRequest);
    }

}
