package org.example.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AuthServiceTest {
    @Test
    void loginChecksBcryptAndReturnsToken() {
        AuthMapper mapper = mock(AuthMapper.class);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        JwtTokenService tokens = new JwtTokenService("0123456789abcdef0123456789abcdef", 28800);
        AuthUserRecord user = user(5L, encoder.encode("correct-password"), true, 0);
        when(mapper.findUserByUsername("owner-a")).thenReturn(user);
        when(mapper.listRoleCodes(5L)).thenReturn(Collections.singletonList("DATA_OWNER"));
        AuthService service = new AuthService(mapper, encoder, tokens, new CurrentUserService());
        AuthDtos.LoginRequest request = new AuthDtos.LoginRequest();
        request.setUsername(" owner-a "); request.setPassword("correct-password");

        String token = service.login(request).getToken();

        assertEquals(5, ((Number) tokens.parse(token).get("uid")).intValue());
    }

    @Test
    void loginDoesNotRevealWhetherUserOrPasswordWasWrong() {
        AuthMapper mapper = mock(AuthMapper.class);
        when(mapper.findUserByUsername("missing")).thenReturn(null);
        AuthService service = new AuthService(mapper, new BCryptPasswordEncoder(4),
                new JwtTokenService("0123456789abcdef0123456789abcdef", 28800),
                new CurrentUserService());
        AuthDtos.LoginRequest request = new AuthDtos.LoginRequest();
        request.setUsername("missing"); request.setPassword("some-password");
        AuthException error = assertThrows(AuthException.class, () -> service.login(request));
        assertEquals("LOGIN_FAILED", error.getErrorCode());
    }

    @Test
    void administratorCanStartAuditedReadOnlyUserSwitch() {
        AuthMapper mapper = mock(AuthMapper.class);
        CurrentUserService currentUsers = mock(CurrentUserService.class);
        ImpersonationAuditMapper audit = mock(ImpersonationAuditMapper.class);
        JwtTokenService tokens = new JwtTokenService("0123456789abcdef0123456789abcdef", 28800);
        AuthUserRecord actor = user(1L, "unused", true, 2);
        actor.setUsername("admin"); actor.setDomainId(null); actor.setDomainEnabled(null);
        AuthUserRecord target = user(5L, "unused", true, 4);
        AuthenticatedUser principal = new AuthenticatedUser(1L, "admin", "Administrator",
                new LinkedHashSet<>(Collections.singletonList("ADMIN")), null, null, null);
        when(currentUsers.requireRole("ADMIN")).thenReturn(principal);
        when(mapper.findUserById(1L)).thenReturn(actor);
        when(mapper.findUserById(5L)).thenReturn(target);
        when(mapper.listRoleCodes(5L)).thenReturn(Collections.singletonList("DATA_OWNER"));
        AuthService service = new AuthService(mapper, new BCryptPasswordEncoder(4), tokens, currentUsers, audit);
        AuthDtos.ImpersonationRequest request = new AuthDtos.ImpersonationRequest();
        request.setUserId(5L);

        String token = service.impersonate(request, "127.0.0.1").getToken();

        assertEquals(Boolean.TRUE, tokens.parse(token).get("imp"));
        verify(audit).insert(1L, "admin", 5L, "owner-a", "START", "127.0.0.1");
    }

    @Test
    void administratorCannotSwitchToAnotherAdministrator() {
        AuthMapper mapper = mock(AuthMapper.class);
        CurrentUserService currentUsers = mock(CurrentUserService.class);
        AuthenticatedUser principal = new AuthenticatedUser(1L, "admin", "Administrator",
                new LinkedHashSet<>(Collections.singletonList("ADMIN")), null, null, null);
        when(currentUsers.requireRole("ADMIN")).thenReturn(principal);
        AuthUserRecord actor = user(1L, "unused", true, 0); actor.setUsername("admin");
        AuthUserRecord target = user(2L, "unused", true, 0); target.setUsername("admin-2");
        when(mapper.findUserById(1L)).thenReturn(actor);
        when(mapper.findUserById(2L)).thenReturn(target);
        when(mapper.listRoleCodes(2L)).thenReturn(Collections.singletonList("ADMIN"));
        AuthService service = new AuthService(mapper, new BCryptPasswordEncoder(4),
                new JwtTokenService("0123456789abcdef0123456789abcdef", 28800), currentUsers, null);
        AuthDtos.ImpersonationRequest request = new AuthDtos.ImpersonationRequest(); request.setUserId(2L);

        AuthException error = assertThrows(AuthException.class,
                () -> service.impersonate(request, "127.0.0.1"));

        assertEquals("IMPERSONATION_TARGET_FORBIDDEN", error.getErrorCode());
    }

    private AuthUserRecord user(long id, String hash, boolean enabled, int version) {
        AuthUserRecord user = new AuthUserRecord();
        user.setUserId(id); user.setUsername("owner-a"); user.setDisplayName("Owner A");
        user.setPasswordHash(hash); user.setEnabled(enabled); user.setTokenVersion(version);
        user.setDomainId(1L); user.setDomainCode("a"); user.setDomainName("A"); user.setDomainEnabled(true);
        return user;
    }
}
