package com.ga.investmentportfolio.DTO.Request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class ChangePasswordRequest {
    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @NotBlank (message = "New password is required")
    @Size(min=8, message = "Password must be at least 8 characters")
    private String newPassword;
}
