package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Response.MessageResponse;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Exception.BusinessRuleException;
import com.ga.investmentportfolio.Exception.InformationExistException;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Model.Asset;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Model.Watchlist;
import com.ga.investmentportfolio.Repository.AssetRepository;
import com.ga.investmentportfolio.Repository.UserRepository;
import com.ga.investmentportfolio.Repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WatchlistService {
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final WatchlistRepository watchlistRepository;

    //add asset to watchlist
    public MessageResponse addToWatchlist(String email, Long assetId) {
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //find asset
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        if (asset.getAssetStatus() == AssetStatus.INACTIVE) {
            throw new BusinessRuleException("Cannot add inactive asset to watchlist");
        }

        //prevent duplicate assets in watchlist
        if(watchlistRepository.existsByUserAndAsset(user, asset)){
            throw new InformationExistException("Asset is already in your watchlist");
        }

        //create a new watchlist record
        Watchlist watchlist = new Watchlist();
        watchlist.setUser(user);
        watchlist.setAsset(asset);

        //save watchlist record
        watchlistRepository.save(watchlist);

        //return successful message
        return new MessageResponse("Asset added to watchlist successfully");
    }

}
