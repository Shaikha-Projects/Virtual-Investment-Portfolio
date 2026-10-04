package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.*;
import com.ga.investmentportfolio.DTO.Response.*;
import com.ga.investmentportfolio.Enums.AssetStatus;
import com.ga.investmentportfolio.Enums.AssetType;
import com.ga.investmentportfolio.Enums.TransactionStatus;
import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Exception.BusinessRuleException;
import com.ga.investmentportfolio.Exception.InformationExistException;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Model.*;
import com.ga.investmentportfolio.Repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AssetService {
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactionRepository;
    private final PortfolioRepository portfolioRepository;

    //get all active asset for user
    public List<AssetResponse> getActiveAssets() {
        //find active assets
            List<Asset> activeAssets = assetRepository.findByAssetStatus(AssetStatus.ACTIVE);

        //return activeAssets using AssetResponse stream/map
        return activeAssets.stream().map(asset -> new  AssetResponse(asset.getSymbol(), asset.getName(),
                asset.getAssetType(), asset.getCurrentPrice(), asset.getAssetStatus())).toList();
    }

    //search/filter assets
    public List<AssetResponse> searchAssets(String search, AssetType assetType){
        //store result
        List<Asset> assets;

        if(search != null && assetType != null ){
            //search + assetType == search by symbol/name and filter by type
            assets = assetRepository.searchByType(AssetStatus.ACTIVE, assetType, search);
        } else if (search != null)  {
            //search provided + no type == search by symbol or name
            assets = assetRepository.findByAssetStatusAndSymbolContainingIgnoreCaseOrAssetStatusAndNameContainingIgnoreCase(
                    AssetStatus.ACTIVE, search, AssetStatus.ACTIVE, search
            );
        } else if (assetType != null) {
            //no search + type provided == filter by asset type
            assets = assetRepository.findByAssetStatusAndAssetType(AssetStatus.ACTIVE, assetType);
        } else {
            //no search, no type == all active assets
            assets = assetRepository.findByAssetStatus(AssetStatus.ACTIVE);
        }

        return assets.stream().map(asset -> new  AssetResponse(asset.getSymbol(), asset.getName(),
                asset.getAssetType(), asset.getCurrentPrice(),asset.getAssetStatus())).toList();


    }

    //create asset for admin
    public AssetResponse createAsset(CreateAssetRequest request){
        //if symbol already exists, throw existing
        if(assetRepository.existsBySymbolIgnoreCase(request.getSymbol())){
            throw new InformationExistException("Asset symbol already exists");
        }

        //create asset
        Asset asset = new Asset();
        asset.setSymbol(request.getSymbol().trim().toUpperCase());
        asset.setName(request.getName());
        asset.setAssetType(request.getAssetType());
        asset.setCurrentPrice(request.getCurrentPrice());
        asset.setAssetStatus(AssetStatus.ACTIVE);

        //save asset
        Asset createdAsset = assetRepository.save(asset);

        return new AssetResponse(createdAsset.getSymbol(), createdAsset.getName(),
                createdAsset.getAssetType(), createdAsset.getCurrentPrice(), createdAsset.getAssetStatus());
    }

    //update asset for admin
    public AssetResponse updateAsset(Long assetId, UpdateAssetRequest request){
        //find asset by id
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        //find if asset symbol already exist
        if(assetRepository.existsBySymbolIgnoreCaseAndIdNot(request.getSymbol(), assetId)){
            throw new InformationExistException("Asset symbol already exists");
        }

        //update existing asset
        asset.setSymbol(request.getSymbol().trim().toUpperCase());
        asset.setName(request.getName());
        asset.setAssetType(request.getAssetType());
        asset.setCurrentPrice(request.getCurrentPrice());

        //save asset
        Asset updatedAsset =  assetRepository.save(asset);

        return new AssetResponse(updatedAsset.getSymbol(), updatedAsset.getName(),
                updatedAsset.getAssetType(), updatedAsset.getCurrentPrice(), updatedAsset.getAssetStatus());
    }

    //activate/deactivate asset for admin
    public AssetResponse updateAssetStatus(Long assetId, UpdateAssetStatusRequest request) {
        //find asset by id
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        asset.setAssetStatus(request.getAssetStatus());

        //save asset
        Asset updatedAsset =  assetRepository.save(asset);

        return new AssetResponse(updatedAsset.getSymbol(), updatedAsset.getName(),
                updatedAsset.getAssetType(), updatedAsset.getCurrentPrice(), updatedAsset.getAssetStatus());
    }

    //list all active and inactive assets for admin
    public List<AssetResponse> getAllAssets() {
        List<Asset> assets = assetRepository.findAll();

        return assets.stream().map(asset -> new  AssetResponse(asset.getSymbol(), asset.getName(),
                asset.getAssetType(), asset.getCurrentPrice(), asset.getAssetStatus())).toList();
    }

    //buy asset for user
    @Transactional
    public TransactionResponse buyAsset(String email, BuyAssetRequest request){
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user portfolio
        Portfolio portfolio = user.getPortfolio();

        //find asset
        Asset asset = assetRepository.findById(request.getAssetId())
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        //check asset status
        if(asset.getAssetStatus() != AssetStatus.ACTIVE){
            throw new BusinessRuleException("Asset is not available for purchase");
        }

        //calculate how much this purchase costs
        BigDecimal totalCost = request.getQuantity().multiply(asset.getCurrentPrice());

        //reject purchase when cashBalance < totalCost
        if(portfolio.getCashBalance().compareTo(totalCost) < 0){
            throw new BusinessRuleException("Insufficient funds");
        }

        //update cash balance
        portfolio.setCashBalance(portfolio.getCashBalance().subtract(totalCost));
        portfolioRepository.save(portfolio); //save portfolio

        //check whether the holding already exists
        //find existing holding
        Optional<Holding> existingHolding = holdingRepository.findByPortfolioAndAsset(portfolio, asset);
        if (existingHolding.isEmpty()) {
            //create first holding
            Holding holding = new Holding();
            holding.setPortfolio(portfolio);
            holding.setAsset(asset);
            holding.setQuantity(request.getQuantity());
            holding.setAverageBuyPrice(asset.getCurrentPrice());
            holdingRepository.save(holding);
        } else { //is holding already exists, update its fields
            Holding holding = existingHolding.get();

            //calculate the old and new invetment
            BigDecimal oldInvestment = holding.getQuantity().multiply(holding.getAverageBuyPrice());
            BigDecimal newInvestment = request.getQuantity().multiply(asset.getCurrentPrice());

            //calculate total investment
            BigDecimal sum = oldInvestment.add(newInvestment);
            //calculate new quantity
            BigDecimal newQuantity = holding.getQuantity().add(request.getQuantity());
            //calculate weighted average
            BigDecimal newAverageBuyPrice = sum.divide(newQuantity, 2 , RoundingMode.HALF_UP);

            //update fields
            holding.setQuantity(newQuantity);
            holding.setAverageBuyPrice(newAverageBuyPrice);

            //save holding
            holdingRepository.save(holding);

        }

        //create BUY transaction
        Transaction transaction = new Transaction();

        //set transaction fields
        transaction.setTransactionType(TransactionType.BUY);
        transaction.setQuantity(request.getQuantity());
        transaction.setPricePerUnit(asset.getCurrentPrice());
        transaction.setTotalAmount(totalCost);
        transaction.setTransactionStatus(TransactionStatus.COMPLETED);
        transaction.setPortfolio(portfolio);
        transaction.setAsset(asset);

        //save transaction
        Transaction savedTransaction = transactionRepository.save(transaction);

        return new TransactionResponse(
                savedTransaction.getId(),
                savedTransaction.getTransactionType(),
                savedTransaction.getAsset().getSymbol(),
                savedTransaction.getQuantity(),
                savedTransaction.getPricePerUnit(),
                savedTransaction.getTotalAmount(),
                savedTransaction.getTransactionStatus(),
                savedTransaction.getCreatedAt(),
                savedTransaction.getPortfolio().getCashBalance()
                );

    }

    //get holdings for user
    public List<HoldingResponse> getHoldings(String email){
        //get user
        User user = userRepository.findByEmailAddress(email).orElseThrow(() ->
                        new InformationNotFoundException("User does not exist"));

        Portfolio portfolio = user.getPortfolio();

        List<Holding> holdings = holdingRepository.findByPortfolio(portfolio);

        return holdings.stream().map(holding -> {

            //calculate current value
            BigDecimal currentValue = holding.getQuantity().multiply(holding.getAsset().getCurrentPrice()).setScale(2, RoundingMode.HALF_UP);;

            //return new HoldingResponse
            return new HoldingResponse(
                    holding.getId(), holding.getAsset().getSymbol(), holding.getAsset().getName(),
                    holding.getQuantity(), holding.getAverageBuyPrice(), holding.getAsset().getCurrentPrice(),
                    currentValue);
        }).toList();
    }

    //sell asset for user
    @Transactional
    public TransactionResponse sellAsset(String email, SellAssetRequest request){
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user portfolio
        Portfolio portfolio = user.getPortfolio();

        //find asset
        Asset asset = assetRepository.findById(request.getAssetId())
                .orElseThrow(() -> new InformationNotFoundException("Asset does not exist"));

        //find the holding
        Holding holding = holdingRepository.findByPortfolioAndAsset(portfolio, asset)
                .orElseThrow(() -> new BusinessRuleException("You do not own this asset"));

        if(holding.getQuantity().compareTo(request.getQuantity()) < 0){
            throw new BusinessRuleException("Insufficient quantity to sell");
        }

        //calculating the sale proceeds
        BigDecimal proceeds = request.getQuantity().multiply(asset.getCurrentPrice());

        //increase the cash balance
        portfolio.setCashBalance(portfolio.getCashBalance().add(proceeds));

        //reduce the holding
        BigDecimal remainingQuantity= holding.getQuantity().subtract(request.getQuantity());

        if (remainingQuantity.compareTo(BigDecimal.ZERO) == 0) {
            // if user sold all shares
            holdingRepository.delete(holding);
        } else {
            // if user still owns some shares
            holding.setQuantity(remainingQuantity);
            holdingRepository.save(holding);
        }

        //update portfolio cash balance
        portfolioRepository.save(portfolio);

        //create SELL transaction
        Transaction transaction = new Transaction();

        //set transaction fields
        transaction.setTransactionType(TransactionType.SELL);
        transaction.setQuantity(request.getQuantity());
        transaction.setPricePerUnit(asset.getCurrentPrice());
        transaction.setTotalAmount(proceeds);
        transaction.setTransactionStatus(TransactionStatus.COMPLETED);
        transaction.setPortfolio(portfolio);
        transaction.setAsset(asset);

        //save transaction
        Transaction savedTransaction = transactionRepository.save(transaction);

        return new TransactionResponse(
                savedTransaction.getId(),
                savedTransaction.getTransactionType(),
                savedTransaction.getAsset().getSymbol(),
                savedTransaction.getQuantity(),
                savedTransaction.getPricePerUnit(),
                savedTransaction.getTotalAmount(),
                savedTransaction.getTransactionStatus(),
                savedTransaction.getCreatedAt(),
                savedTransaction.getPortfolio().getCashBalance()
        );

    }

    //get transaction
    public List<TransactionHistoryResponse> getTransactionHistory(String email, TransactionType type, String symbol) {
        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user portfolio
        Portfolio portfolio = user.getPortfolio();

        //create transactions list
        List<Transaction> transactions;

        if (type == null && symbol == null) {
            //get all transactions
            transactions = transactionRepository.findByPortfolioOrderByCreatedAtDesc(portfolio);
        } else if (type != null && symbol == null) {
            //get transaction filter by transaction type
            transactions = transactionRepository.findByPortfolioAndTransactionTypeOrderByCreatedAtDesc(portfolio, type);
        } else if(type == null && symbol != null){
            //get transaction filter by asset symbol
            transactions = transactionRepository.findByPortfolioAndAssetSymbolOrderByCreatedAtDesc(portfolio, symbol);
        } else {
            // get transaction filter by transaction type and asset symbol
            transactions = transactionRepository.findByPortfolioAndTransactionTypeAndAssetSymbolOrderByCreatedAtDesc(portfolio, type, symbol);
        }

        //return transactions
        return transactions.stream().map(transaction -> new TransactionHistoryResponse(
                transaction.getId(), transaction.getTransactionType(),
                transaction.getAsset().getSymbol(), transaction.getQuantity(),
                transaction.getPricePerUnit(), transaction.getTotalAmount(),
                transaction.getTransactionStatus(), transaction.getCreatedAt()
        ) ).toList();

    }

    //get portfolio performance
    public PortfolioPerformanceResponse getPortfolioPerformance(String email) {

        //find user by email
        User user = userRepository.findByEmailAddress(email)
                .orElseThrow(() -> new InformationNotFoundException("User does not exist"));

        //get user portfolio
        Portfolio portfolio = user.getPortfolio();

        //get user holdings
        List<Holding> holdings = holdingRepository.findByPortfolio(portfolio);

        //calculate holdingsValue
        //start with zero, there might be many values
        BigDecimal holdingsValue = BigDecimal.ZERO;

        //calculate totalCostBasis
        BigDecimal totalCostBasis = BigDecimal.ZERO;

        for (Holding holding : holdings){
            // calculate this holding's current value
            //multiplying the quantity of each asset by its current price
            BigDecimal currentValue = holding.getQuantity().multiply(holding.getAsset().getCurrentPrice());
            holdingsValue = holdingsValue.add(currentValue);

            //cost basis
            //multiplying the quantity of each asset by average buy price
            BigDecimal holdingCost = holding.getQuantity().multiply(holding.getAverageBuyPrice());
            totalCostBasis = totalCostBasis.add(holdingCost);
        }

        //calculate unrealized gain loss
        //holdings value - the cost basis
        BigDecimal unrealizedGainLoss = holdingsValue.subtract(totalCostBasis);

        //calculate totalPortfolio value
        //cash balance + the current holdings value
        BigDecimal totalPortfolioValue = portfolio.getCashBalance().add(holdingsValue);

        //return PortfolioPerformanceResponse
        return new PortfolioPerformanceResponse(
                portfolio.getCashBalance(), holdingsValue, totalPortfolioValue, totalCostBasis, unrealizedGainLoss
        );
    }


}
