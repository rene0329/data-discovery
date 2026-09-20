package org.example.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private AuthUserRecord user(long id, String hash, boolean enabled, int version) {
        AuthUserRecord user = new AuthUserRecord();
        user.setUserId(id); user.setUsername("owner-a"); user.setDisplayName("Owner A");
        user.setPasswordHash(hash); user.setEnabled(enabled); user.setTokenVersion(version);
        user.setDomainId(1L); user.setDomainCode("a"); user.setDomainName("A"); user.setDomainEnabled(true);
        return user;
    }
}
