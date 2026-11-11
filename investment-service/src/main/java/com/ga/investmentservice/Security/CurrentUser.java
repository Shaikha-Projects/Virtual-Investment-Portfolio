package com.ga.investmentservice.Security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;

@Component
public class CurrentUser {
    //keep existing service email arguments tied to the verified identity
    public Long getUserId(String email) {
        Long userId = getUserId();
        if (!SecurityContextHolder.getContext().getAuthentication().getName().equals(email)) {
            throw new AccessDeniedException("User must match the authenticated user");
        }
        return userId;
    }

    //use the identity established by JWT validation, never a request parameter or header.
    public Long getUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        return user.getUserId();
    }
}
