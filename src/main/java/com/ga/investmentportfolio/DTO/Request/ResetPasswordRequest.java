package com.ga.investmentportfolio.DTO.Request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class ResetPasswordRequest {

    @NotBlank (message = "Token is required")
    private String token;

    @NotBlank(message = "New password is required")
    @Size(min=8, message = "Password must be at least 8 characters")
    private String newPassword;
}
