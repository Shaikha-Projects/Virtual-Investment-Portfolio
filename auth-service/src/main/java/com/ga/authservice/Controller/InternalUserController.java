package com.ga.authservice.Controller;

import com.ga.authservice.DTO.Response.UserIdentityResponse;
import com.ga.authservice.Model.User;
import com.ga.authservice.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor

public class InternalUserController {
    private final UserRepository userRepository;

    //find user by ID
    @GetMapping("/{userId}")
    public UserIdentityResponse getUserById(@PathVariable Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User does not exist"));

        return toResponse(user);
    }

    //find user by email
    @GetMapping("/by-email")
    public UserIdentityResponse getUserByEmail(@RequestParam String emailAddress) {
        User user = userRepository.findByEmailAddress(emailAddress)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User does not exist"));

        return toResponse(user);
    }

    private UserIdentityResponse toResponse(User user) {
        return new UserIdentityResponse(user.getId(), user.getEmailAddress(), user.getRole().name(), user.getStatus().name());
    }
}
