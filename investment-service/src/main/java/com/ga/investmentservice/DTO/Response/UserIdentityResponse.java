package com.ga.investmentservice.DTO.Response;

public record UserIdentityResponse (Long userId,
                                    String emailAddress,
                                    String role,
                                    String status){
}
