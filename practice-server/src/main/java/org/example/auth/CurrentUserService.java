package org.example.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    public AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser)) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "authentication is required");
        }
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    public AuthenticatedUser require() {
        return currentUser();
    }

    public boolean hasRole(String role) {
        return currentUser().hasRole(role);
    }

    public AuthenticatedUser requireRole(String role) {
        AuthenticatedUser user = currentUser();
        if (!user.hasRole(role)) {
            throw new AuthException(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "required role: " + role);
        }
        return user;
    }
}
