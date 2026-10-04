package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Response.MessageResponse;
import com.ga.investmentportfolio.DTO.Response.WatchlistResponse;
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

import java.util.List;

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

    //get all user asset
    public List<WatchlistResponse> getWatchlist(String email){
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user watchlist
        List<Watchlist> watchlists = watchlistRepository.findByUser(user);

        return watchlists.stream().map(watchlist -> new WatchlistResponse(
                watchlist.getId(), watchlist.getAsset().getSymbol(),
                watchlist.getAsset().getName(), watchlist.getAsset().getCurrentPrice(),
                watchlist.getCreatedAt()
        )).toList();
    }

    //remove record(asset) from watchlist
    public MessageResponse removeFromWatchlist(String email, Long watchlistId){
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user watchlist
        Watchlist watchlist = watchlistRepository.findByIdAndUser(watchlistId, user)
                .orElseThrow(() -> new InformationNotFoundException("Watchlist entry does not exist"));

        //remove
        watchlistRepository.delete(watchlist);


        //return removal message
        return new MessageResponse("Asset removed from watchlist successfully");

    }

}
