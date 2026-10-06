package com.ga.investmentportfolio.Controller;

import com.ga.investmentportfolio.DTO.Request.UpdateProfileRequest;
import com.ga.investmentportfolio.DTO.Response.ProfileResponse;
import com.ga.investmentportfolio.Repository.UserRepository;
import com.ga.investmentportfolio.Service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/profile")
@AllArgsConstructor
@Tag(name = "Profile", description = "Manage authenticated user's profile and profile picture")
@SecurityRequirement(name = "bearerAuth")
public class ProfileController {
    private ProfileService profileService;
    private UserRepository userRepository;

    @Operation(
            summary = "View profile",
            description = "Returns profile information of authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User does not exist")})
    @GetMapping
    public ProfileResponse viewProfile(Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();

        return profileService.getProfile(emailAddress);
    }

    @Operation(
            summary = "Update profile",
            description = "Updates the profile information of the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid profile data"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User does not exist")})
    @PatchMapping
    public ProfileResponse updateProfile(@Valid @RequestBody UpdateProfileRequest request, Authentication authentication) {
        // get authenticated user's email
        String emailAddress = authentication.getName();
        return profileService.updateProfile(emailAddress, request);
    }

    @Operation(
            summary = "Upload profile picture",
            description = "Uploads or replaces profile picture of authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile picture uploaded successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid profile picture"),
            @ApiResponse(responseCode = "401", description = "Authentication required"),
            @ApiResponse(responseCode = "404", description = "User does not exist")})
    @PutMapping("/picture")
    public ProfileResponse updateProfilePicture(@RequestParam("picture") MultipartFile image, Authentication authentication){
        // get authenticated user's email
        String emailAddress = authentication.getName();
        return profileService.updateProfilePicture(emailAddress, image);
    }


}
