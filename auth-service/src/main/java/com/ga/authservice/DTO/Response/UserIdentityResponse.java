package com.ga.authservice.DTO.Response;

public record UserIdentityResponse (
        Long userId,
        String emailAddress,
        String role,
        String status
){
}
