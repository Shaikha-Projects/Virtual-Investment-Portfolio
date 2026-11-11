package com.ga.investmentservice.Service;

import com.ga.investmentservice.DTO.Response.MessageResponse;
import com.ga.investmentservice.DTO.Response.WatchlistResponse;
import com.ga.investmentservice.Enums.AssetStatus;
import com.ga.investmentservice.Exception.BusinessRuleException;
import com.ga.investmentservice.Exception.InformationExistException;
import com.ga.investmentservice.Exception.InformationNotFoundException;
import com.ga.investmentservice.Model.Asset;
import com.ga.investmentservice.Model.Watchlist;
import com.ga.investmentservice.Repository.AssetRepository;
import com.ga.investmentservice.Security.CurrentUser;
import com.ga.investmentservice.Repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WatchlistService {
    private final CurrentUser currentUser;
    private final AssetRepository assetRepository;
    private final WatchlistRepository watchlistRepository;

    //add asset to watchlist
    @PreAuthorize("hasRole('USER')")
    public MessageResponse addToWatchlist(String email, Long assetId) {
        //find user by email
        Long userId = currentUser.getUserId(email);

        //find asset
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        if (asset.getAssetStatus() == AssetStatus.INACTIVE) {
            throw new BusinessRuleException("Cannot add inactive asset to watchlist");
        }

        //prevent duplicate assets in watchlist
        if(watchlistRepository.existsByUserIdAndAsset(userId, asset)){
            throw new InformationExistException("Asset is already in your watchlist");
        }

        //create a new watchlist record
        Watchlist watchlist = new Watchlist();
        watchlist.setUserId(userId);
        watchlist.setAsset(asset);

        //save watchlist record
        watchlistRepository.save(watchlist);

        //return successful message
        return new MessageResponse("Asset added to watchlist successfully");
    }

    //get all user asset
    @PreAuthorize("hasRole('USER')")
    public List<WatchlistResponse> getWatchlist(String email){
        //find user by email
        Long userId = currentUser.getUserId(email);

        //get user watchlist
        List<Watchlist> watchlists = watchlistRepository.findByUserId(userId);

        return watchlists.stream().map(watchlist -> new WatchlistResponse(
                watchlist.getId(), watchlist.getAsset().getSymbol(),
                watchlist.getAsset().getName(), watchlist.getAsset().getCurrentPrice(),
                watchlist.getCreatedAt()
        )).toList();
    }

    //remove record(asset) from watchlist
    @PreAuthorize("hasRole('USER')")
    public MessageResponse removeFromWatchlist(String email, Long watchlistId){
        //find user by email
        Long userId = currentUser.getUserId(email);

        //get user watchlist
        Watchlist watchlist = watchlistRepository.findByIdAndUserId(watchlistId, userId)
                .orElseThrow(() -> new InformationNotFoundException("Watchlist entry does not exist"));

        //remove
        watchlistRepository.delete(watchlist);


        //return removal message
        return new MessageResponse("Asset removed from watchlist successfully");

    }

}
