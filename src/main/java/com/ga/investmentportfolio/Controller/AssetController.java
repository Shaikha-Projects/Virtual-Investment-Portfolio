package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Service.AssetService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetController {
    private final AssetService assetService;

    @GetMapping
    public List<AssetResponse> viewAssets(@RequestParam(required = false) String search,
                                          @RequestParam(required = false) AssetType type){
        return assetService.searchAssets(search, type);
    }
}
