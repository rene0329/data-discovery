package org.example.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentUserServiceTest {
    private final CurrentUserService service = new CurrentUserService();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void exposesAuthenticatedPrincipalAndRole() {
        AuthenticatedUser user = new AuthenticatedUser(3L, "auditor", "Auditor",
                Collections.singleton("AUDITOR"), null, null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
        assertEquals(3L, service.currentUser().getUserId());
        assertTrue(service.hasRole("auditor"));
    }

    @Test
    void missingIdentityProducesUnauthorizedError() {
        AuthException error = assertThrows(AuthException.class, service::currentUser);
        assertEquals("AUTH_REQUIRED", error.getErrorCode());
    }
}
