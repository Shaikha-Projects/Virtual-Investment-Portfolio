package com.ga.authservice.DTO.Response;

import com.ga.authservice.Enums.Role;

public record SessionResponse(String emailAddress, Role role) {}
