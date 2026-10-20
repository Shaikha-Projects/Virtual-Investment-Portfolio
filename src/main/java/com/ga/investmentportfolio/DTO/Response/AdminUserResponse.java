package com.ga.investmentportfolio.DTO.Response;

import com.ga.investmentportfolio.Enums.Role;
import com.ga.investmentportfolio.Enums.UserStatus;

public record AdminUserResponse(Long id, String firstName, String lastName,
                                String emailAddress, Role role, UserStatus status) {}
