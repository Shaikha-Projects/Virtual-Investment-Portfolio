package com.ga.investmentportfolio.DTO.Request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Getter
public class RegisterRequest {
    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank (message = "Phone number is required")
    @Pattern(regexp = "\\d{8}", message = "Phone number must be exactly 8 numbers") // \d = a digit (0-9), {8} numbers
    private String phoneNumber;

    @NotBlank (message = "Email Address is required")
    @Email(message = "Email Address must be valid")
    private String emailAddress;

    @NotBlank (message = "Password is required")
    @Size(min=8, message = "Password must be at leat 8 characters")
    private String password;
}
