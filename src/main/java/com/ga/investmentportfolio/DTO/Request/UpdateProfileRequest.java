package com.ga.investmentportfolio.DTO.Request;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
public class UpdateProfileRequest {
    private String firstName;
    private String lastName;

    @Pattern(regexp = "\\d{8}", message = "Phone number must contain exactly 8 digits")
    private String phoneNumber;
}
