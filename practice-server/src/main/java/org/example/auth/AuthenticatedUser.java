package org.example.auth;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class AuthenticatedUser implements Serializable {
    private final Long userId;
    private final String username;
    private final String displayName;
    private final Set<String> roles;
    private final Long domainId;
    private final String domainCode;
    private final String domainName;
    private final boolean impersonated;
    private final Long actorUserId;
    private final String actorUsername;

    public AuthenticatedUser(Long userId, String username, String displayName, Set<String> roles,
                             Long domainId, String domainCode, String domainName) {
        this(userId, username, displayName, roles, domainId, domainCode, domainName,
                false, null, null);
    }

    public AuthenticatedUser(Long userId, String username, String displayName, Set<String> roles,
                             Long domainId, String domainCode, String domainName,
                             boolean impersonated, Long actorUserId, String actorUsername) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
        this.domainId = domainId;
        this.domainCode = domainCode;
        this.domainName = domainName;
        this.impersonated = impersonated;
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
    }

    public Long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public Set<String> getRoles() { return roles; }
    public Long getDomainId() { return domainId; }
    public String getDomainCode() { return domainCode; }
    public String getDomainName() { return domainName; }
    public boolean isImpersonated() { return impersonated; }
    public Long getActorUserId() { return actorUserId; }
    public String getActorUsername() { return actorUsername; }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role.trim().toUpperCase());
    }
}
