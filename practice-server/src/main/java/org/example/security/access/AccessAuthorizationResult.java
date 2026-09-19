package org.example.security.access;

import java.time.Instant;

public class AccessAuthorizationResult {
    private String token;
    private String tokenType;
    private Instant expiresAt;
    private String jti;
    private String principal;
    private AccessScope scope;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
    public String getPrincipal() { return principal; }
    public void setPrincipal(String principal) { this.principal = principal; }
    public AccessScope getScope() { return scope; }
    public void setScope(AccessScope scope) { this.scope = scope; }
}
