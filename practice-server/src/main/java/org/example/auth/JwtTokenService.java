package org.example.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtTokenService {
    private final String signingKey;
    private final long ttlMillis;

    public JwtTokenService(@Value("${app.auth.jwt.signing-key:}") String signingKey,
                           @Value("${app.auth.jwt.ttl-seconds:28800}") long ttlSeconds) {
        this.signingKey = signingKey == null ? "" : signingKey.trim();
        this.ttlMillis = ttlSeconds * 1000L;
    }

    public String create(AuthUserRecord user, java.util.Collection<String> roles) {
        requireConfigured();
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(user.getUsername())
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlMillis))
                .claim("uid", user.getUserId())
                .claim("ver", user.getTokenVersion())
                .claim("roles", roles)
                .claim("domainId", user.getDomainId())
                .claim("domainCode", user.getDomainCode())
                .signWith(SignatureAlgorithm.HS256, signingKey.getBytes(StandardCharsets.UTF_8))
                .compact();
    }

    public Claims parse(String token) {
        requireConfigured();
        return Jwts.parser().setSigningKey(signingKey.getBytes(StandardCharsets.UTF_8))
                .parseClaimsJws(token).getBody();
    }

    private void requireConfigured() {
        if (signingKey.length() < 32) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_NOT_CONFIGURED",
                    "JWT signing key must contain at least 32 characters");
        }
    }
}
