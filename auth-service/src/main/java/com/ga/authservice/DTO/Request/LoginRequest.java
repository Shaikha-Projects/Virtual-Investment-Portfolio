package com.ga.authservice.DTO.Request;

import lombok.Getter;

@Getter
public class LoginRequest {
    private String emailAddress;
    private String password;
}
