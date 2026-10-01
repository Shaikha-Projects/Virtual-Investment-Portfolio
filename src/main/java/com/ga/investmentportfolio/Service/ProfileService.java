package com.ga.investmentportfolio.Service;

import com.ga.investmentportfolio.DTO.Request.UpdateProfileRequest;
import com.ga.investmentportfolio.DTO.Response.ProfileResponse;
import com.ga.investmentportfolio.Exception.InformationNotFoundException;
import com.ga.investmentportfolio.Model.User;
import com.ga.investmentportfolio.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}
