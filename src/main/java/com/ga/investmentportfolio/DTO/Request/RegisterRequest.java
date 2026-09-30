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
    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    @NotBlank
    @Pattern(regexp = "\\d{8}") // \d = a digit (0-9), {8} numbers
    private String phoneNumber;

    @NotBlank
    @Email
    private String emailAddress;

    @NotBlank
    @Size(min=8)
    private String password;
}
