package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.UpdateAssetRequest;
import com.ga.investmentportfolio.DTO.Response.AssetResponse;
import com.ga.investmentportfolio.DTO.Response.MessageResponse;
import com.ga.investmentportfolio.DTO.Response.WatchlistResponse;
import com.ga.investmentportfolio.Service.WatchlistService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/watchlist")
@AllArgsConstructor
public class WatchlistController {
    private final WatchlistService watchlistService;

    @PostMapping("/{id}")
    public MessageResponse addToWatchlist(@PathVariable Long id,
                                          Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();
        return watchlistService.addToWatchlist(emailAddress, id);
    }

    @GetMapping
    public List<WatchlistResponse> getWatchlist(Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();
        return watchlistService.getWatchlist(emailAddress);
    }
}
