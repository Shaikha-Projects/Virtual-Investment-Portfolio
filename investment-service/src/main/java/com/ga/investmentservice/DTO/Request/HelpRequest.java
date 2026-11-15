package com.ga.investmentservice.DTO.Request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class HelpRequest {
    @NotBlank(message = "Question is required")
    @Size(max = 2000, message = "Question must not exceed 2000 characters")
    private String question;
}
