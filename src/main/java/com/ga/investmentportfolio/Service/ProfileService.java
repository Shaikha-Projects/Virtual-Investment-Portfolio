package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.UpdateProfileRequest;
import com.ga.investmentportfolio.DTO.Response.ProfileResponse;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Exception.InvalidFileException;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final UserRepository userRepository;

    public ProfileResponse getProfile(String emailAddress){
        //find user by email
        User user = userRepository.findByEmailAddress(emailAddress)
                .orElseThrow(() -> new InformationNotFoundException("User not found"));

        //return user profile using ProfileResponse
        return new ProfileResponse(user.getFirstName(),
                user.getLastName(),
                user.getPhoneNumber(),
                user.getEmailAddress(),
                user.getProfilePicture());

    }

    public ProfileResponse updateProfile(String emailAddress, UpdateProfileRequest request){
        //find user by email
        User user = userRepository.findByEmailAddress(emailAddress)
                .orElseThrow(() -> new InformationNotFoundException("User not found"));


        //update user profile fields
        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }

        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }

        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }

        //save updated user
        userRepository.save(user);

        //return updated user using ProfileResponse
        return new ProfileResponse(user.getFirstName(),
                user.getLastName(),
                user.getPhoneNumber(),
                user.getEmailAddress(),
                user.getProfilePicture());

    }

    public ProfileResponse updateProfilePicture(String emailAddress, MultipartFile image){
        //find user by email
        User user = userRepository.findByEmailAddress(emailAddress)
                .orElseThrow(() -> new InformationNotFoundException("User not found"));

        //validate the uploaded image
        if (image.isEmpty()){
            throw new InvalidFileException("Profile picture cannot be empty");
        }

        //validate that the uploaded file is actually an image
        String contentType = image.getContentType();
        if(contentType == null || !contentType.startsWith("image/")){
            throw new InvalidFileException("Only image files are allowed");
        }

        //get original image file name
        String originalFileName = image.getOriginalFilename();

        //validate original image file name
        if (originalFileName == null || !originalFileName.contains(".")) {
            throw new InvalidFileException("Image file must have a valid extension");
        }

        //extract extension
        int dotIndex = originalFileName.lastIndexOf(".");
        String extension = originalFileName.substring(dotIndex);

        //generate a unique filename for image
        String uniqueFileName = UUID.randomUUID() + extension;

        //creating paths
        Path uploadPath = Paths.get("uploads/profile-pictures");
        Path filePath = uploadPath.resolve(uniqueFileName);

        try {
            // create directory if it doesn't exist
            Files.createDirectories(uploadPath);

            // save uploaded image
            Files.copy(image.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            throw new InvalidFileException("Failed to save profile picture: " + e.getMessage());
        }

        //delete old profile picture if it exists
        if (user.getProfilePicture() != null) {
            Path oldFilePath = Paths.get(user.getProfilePicture());

            try {
                Files.deleteIfExists(oldFilePath);
            } catch (IOException e) {
                throw new InvalidFileException("Failed to replace profile picture: " + e.getMessage());
            }
        }

        //store image path in user
        user.setProfilePicture(filePath.toString());

        //save user
        userRepository.save(user);

        //return updated user using ProfileResponse
        return new ProfileResponse(user.getFirstName(),
                user.getLastName(),
                user.getPhoneNumber(),
                user.getEmailAddress(),
                user.getProfilePicture());

    }

}
