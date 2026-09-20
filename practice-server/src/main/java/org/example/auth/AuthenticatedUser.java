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

    public AuthenticatedUser(Long userId, String username, String displayName, Set<String> roles,
                             Long domainId, String domainCode, String domainName) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
        this.domainId = domainId;
        this.domainCode = domainCode;
        this.domainName = domainName;
    }

    public Long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public Set<String> getRoles() { return roles; }
    public Long getDomainId() { return domainId; }
    public String getDomainCode() { return domainCode; }
    public String getDomainName() { return domainName; }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role.trim().toUpperCase());
    }
}
