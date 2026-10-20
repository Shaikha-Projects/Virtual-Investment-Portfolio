package com.ga.investmentportfolio.DTO.Response;

import com.ga.investmentportfolio.Enums.Role;

public record SessionResponse(String emailAddress, Role role) {}
