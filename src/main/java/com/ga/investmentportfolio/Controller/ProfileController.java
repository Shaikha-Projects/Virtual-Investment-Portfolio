package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.BuyAssetRequest;
import com.ga.investmentportfolio.DTO.Request.ChangePasswordRequest;
import com.ga.investmentportfolio.DTO.Request.UpdateProfileRequest;
import com.ga.investmentportfolio.DTO.Response.ProfileResponse;
import com.ga.investmentportfolio.DTO.Response.RegisterResponse;
import com.ga.investmentportfolio.DTO.Response.TransactionHistoryResponse;
import com.ga.investmentportfolio.DTO.Response.TransactionResponse;
import com.ga.investmentportfolio.Enums.TransactionType;
import com.ga.investmentportfolio.Repository.UserRepository;
import com.ga.investmentportfolio.Service.ProfileService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/profile")
@AllArgsConstructor
public class ProfileController {
    private ProfileService profileService;
    private UserRepository userRepository;

    @GetMapping
    public ProfileResponse viewProfile(Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();

        return profileService.getProfile(emailAddress);
    }

    @PatchMapping
    public ProfileResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request, Authentication authentication) {

        // get authenticated user's email
        String emailAddress = authentication.getName();

        return profileService.updateProfile(emailAddress, request);

    }

    @PutMapping("/picture")
    public ProfileResponse updateProfilePicture(@RequestParam("picture") MultipartFile image, Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();

        return profileService.updateProfilePicture(emailAddress, image);

    }


}
