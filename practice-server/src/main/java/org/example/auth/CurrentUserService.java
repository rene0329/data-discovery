package org.example.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CurrentUserService {
    public AuthenticatedUser currentUser() {
        return findCurrentUser().orElseThrow(() ->
                new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "authentication is required"));
    }

    /**
     * The JWT user of the current request, or empty when the request carries no
     * user identity (anonymous, internal Agent credentials, background threads).
     */
    public Optional<AuthenticatedUser> findCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser)) {
            return Optional.empty();
        }
        return Optional.of((AuthenticatedUser) authentication.getPrincipal());
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
