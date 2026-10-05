package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.CreateAssetRequest;
import com.ga.investmentportfolio.DTO.Request.UpdateAssetRequest;
import com.ga.investmentportfolio.DTO.Request.UpdateAssetStatusRequest;
import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.Service.AssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/assets")
@RequiredArgsConstructor
@Tag(name = "Admin Assets", description = "Administrative asset management operations restricted to ADMIN users")
@SecurityRequirement(name = "bearerAuth")
public class AdminAssetController {
    private final AssetService assetService;

    @Operation(
            summary = "Create asset",
            description = "Creates a new investment asset. ADMIN access is required.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Asset created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid asset data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "ADMIN access required"),
            @ApiResponse(responseCode = "409", description = "Asset symbol already exists")})
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AssetResponse createAsset(@Valid @RequestBody CreateAssetRequest assetRequest,
                                     Authentication authentication){
        return assetService.createAsset(authentication.getName(), assetRequest);
    }

    @Operation(
            summary = "Update asset",
            description = "Updates an existing investment asset. ADMIN access is required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asset updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid asset data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "ADMIN access required"),
            @ApiResponse(responseCode = "404", description = "Asset does not exist"),
            @ApiResponse(responseCode = "409", description = "Asset symbol already exists")})
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AssetResponse updateAsset(@PathVariable Long id,
                                     @Valid @RequestBody UpdateAssetRequest assetRequest,
                                     Authentication authentication){
        return assetService.updateAsset(authentication.getName(), id, assetRequest);
    }

    @Operation(
            summary = "Update asset status",
            description = "Activates or deactivates an investment asset. ADMIN access is required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asset status updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid asset status"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "ADMIN access required"),
            @ApiResponse(responseCode = "404", description = "Asset does not exist")})
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public AssetResponse updateAssetStatus(@PathVariable Long id,
                                            @Valid @RequestBody UpdateAssetStatusRequest assetRequest,
                                           Authentication authentication){
        return assetService.updateAssetStatus(authentication.getName(),id, assetRequest);
    }

    @Operation(
            summary = "View all assets",
            description = "Returns all investment assets, including inactive assets. ADMIN access is required.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assets retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "403", description = "ADMIN access required")})
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<AssetResponse> viewAssets(){
        return assetService.getAllAssets();
    }



}
