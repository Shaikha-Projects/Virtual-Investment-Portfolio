package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.CreateAssetRequest;
import com.ga.investmentportfolio.DTO.Request.UpdateAssetRequest;
import com.ga.investmentportfolio.DTO.Request.UpdateAssetStatusRequest;
import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.Service.AssetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AssetResponse updateAsset(@PathVariable Long id,
                                     @Valid @RequestBody UpdateAssetRequest assetRequest){
        return assetService.updateAsset(id, assetRequest);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public AssetResponse updateAssetStatus(@PathVariable Long id,
                                     @Valid @RequestBody UpdateAssetStatusRequest assetRequest){
        return assetService.updateAssetStatus(id, assetRequest);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AssetResponse> viewAssets(){
        return assetService.getAllAssets();
    }



}
