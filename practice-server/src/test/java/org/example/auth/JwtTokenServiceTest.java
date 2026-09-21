package org.example.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenServiceTest {
    @Test
    void tokenContainsIdentityVersionRolesAndEightHourExpiry() {
        JwtTokenService service = new JwtTokenService("0123456789abcdef0123456789abcdef", 28800);
        AuthUserRecord user = new AuthUserRecord();
        user.setUserId(7L); user.setUsername("owner-a"); user.setTokenVersion(3);
        user.setDomainId(11L); user.setDomainCode("north");

        Claims claims = service.parse(service.create(user, Arrays.asList("DATA_OWNER")));

        assertEquals("owner-a", claims.getSubject());
        assertEquals(7, ((Number) claims.get("uid")).intValue());
        assertEquals(3, ((Number) claims.get("ver")).intValue());
        assertEquals(11, ((Number) claims.get("domainId")).intValue());
        long lifetime = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertEquals(28_800_000L, lifetime);
    }

    @Test
    void refusesToIssueTokenWithoutStrongConfiguredKey() {
        JwtTokenService service = new JwtTokenService("short", 28800);
        AuthUserRecord user = new AuthUserRecord();
        assertThrows(AuthException.class, () -> service.create(user, Arrays.asList("ADMIN")));
    }

    @Test
    void impersonationTokenContainsActorAndHasShorterLifetime() {
        JwtTokenService service = new JwtTokenService("0123456789abcdef0123456789abcdef", 28800);
        AuthUserRecord target = new AuthUserRecord();
        target.setUserId(7L); target.setUsername("owner-a"); target.setTokenVersion(3);
        AuthUserRecord actor = new AuthUserRecord();
        actor.setUserId(1L); actor.setUsername("admin"); actor.setTokenVersion(5);

        Claims claims = service.parse(service.createImpersonated(target,
                Arrays.asList("DATA_OWNER"), actor));

        assertEquals(Boolean.TRUE, claims.get("imp"));
        assertEquals(1, ((Number) claims.get("actorUid")).intValue());
        assertEquals(5, ((Number) claims.get("actorVer")).intValue());
        assertEquals("admin", claims.get("actorUsername"));
        long lifetime = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertTrue(lifetime <= 3_600_000L);
    }
}
