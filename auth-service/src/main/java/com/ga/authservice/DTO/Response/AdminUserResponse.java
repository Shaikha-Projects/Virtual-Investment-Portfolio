package com.ga.authservice.DTO.Response;

import com.ga.authservice.Enums.Role;
import com.ga.authservice.Enums.UserStatus;

public record AdminUserResponse(Long id, String firstName, String lastName,
                                String emailAddress, Role role, UserStatus status) {}
