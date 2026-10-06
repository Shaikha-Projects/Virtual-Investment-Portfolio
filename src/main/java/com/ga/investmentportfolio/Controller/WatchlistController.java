package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Response.MessageResponse;
import com.ga.investmentportfolio.DTO.Response.WatchlistResponse;
import com.ga.investmentportfolio.Service.WatchlistService;
import lombok.AllArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@RestController
@RequestMapping("/watchlist")
@AllArgsConstructor
@Tag(name = "Watchlist", description = "Manage the authenticated user's investment watchlist")
@SecurityRequirement(name = "bearerAuth")
public class WatchlistController {
    private final WatchlistService watchlistService;

    @Operation(
            summary = "Add asset to watchlist",
            description = "Adds an active investment asset to the authenticated user's watchlist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asset added to watchlist successfully"),
            @ApiResponse(responseCode = "400", description = "Inactive asset cannot be added"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User or asset does not exist"),
            @ApiResponse(responseCode = "409", description = "Asset is already in the watchlist")})
    @PostMapping("/{id}")
    public MessageResponse addToWatchlist(@PathVariable Long id,
                                          Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();
        return watchlistService.addToWatchlist(emailAddress, id);
    }

    @Operation(
            summary = "View watchlist",
            description = "Returns all assets currently in the authenticated user's watchlist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Watchlist retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User does not exist")})
    @GetMapping
    public List<WatchlistResponse> getWatchlist(Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();
        return watchlistService.getWatchlist(emailAddress);
    }

    @Operation(
            summary = "Remove asset from watchlist",
            description = "Removes a watchlist entry belonging to the authenticated user using its watchlist entry ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asset removed from watchlist successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User or watchlist entry does not exist")})
    @DeleteMapping("/{id}")
    public MessageResponse removeFromWatchlist(@PathVariable Long id,
                                                       Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();
        return watchlistService.removeFromWatchlist(emailAddress, id);
    }

}
