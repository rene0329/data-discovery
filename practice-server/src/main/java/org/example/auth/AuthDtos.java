package org.example.auth;

import java.util.LinkedHashSet;
import java.util.Set;

public final class AuthDtos {
    private AuthDtos() { }

    public static class LoginRequest {
        private String username;
        private String password;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class LoginResponse {
        private final String token;
        public LoginResponse(String token) { this.token = token; }
        public String getToken() { return token; }
    }

    public static class ImpersonationRequest {
        private Long userId;
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
    }

    public static class DomainInfo {
        private final Long id;
        private final String code;
        private final String name;
        public DomainInfo(Long id, String code, String name) {
            this.id = id; this.code = code; this.name = name;
        }
        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getName() { return name; }
    }

    public static class MeResponse {
        private final Long id;
        private final String username;
        private final String displayName;
        private final Set<String> roles;
        private final DomainInfo domain;
        private final Long domainId;
        private final String domainName;
        private final boolean impersonated;
        private final Long actorUserId;
        private final String actorUsername;
        public MeResponse(AuthenticatedUser user) {
            this.id = user.getUserId();
            this.username = user.getUsername();
            this.displayName = user.getDisplayName();
            this.roles = new LinkedHashSet<>(user.getRoles());
            this.domainId = user.getDomainId();
            this.domainName = user.getDomainName();
            this.impersonated = user.isImpersonated();
            this.actorUserId = user.getActorUserId();
            this.actorUsername = user.getActorUsername();
            this.domain = domainId == null ? null
                    : new DomainInfo(domainId, user.getDomainCode(), user.getDomainName());
        }
        public Long getId() { return id; }
        public String getUsername() { return username; }
        public String getDisplayName() { return displayName; }
        public Set<String> getRoles() { return roles; }
        public DomainInfo getDomain() { return domain; }
        public Long getDomainId() { return domainId; }
        public String getDomainName() { return domainName; }
        public boolean isImpersonated() { return impersonated; }
        public Long getActorUserId() { return actorUserId; }
        public String getActorUsername() { return actorUsername; }
    }

    public static class DomainRequest {
        private String code;
        private String name;
        private String siteCode;
        private boolean siteCodePresent;
        private String description;
        private Boolean enabled;
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSiteCode() { return siteCode; }
        /** Jackson calls this for an explicit {@code "siteCode": null} too, so PATCH can clear the site. */
        public void setSiteCode(String siteCode) { this.siteCode = siteCode; this.siteCodePresent = true; }
        /** Whether the body named siteCode at all; an absent field leaves the site unchanged on update. */
        public boolean hasSiteCode() { return siteCodePresent; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }

    public static class UserRequest {
        private String username;
        private String password;
        private String displayName;
        private Long domainId;
        private Boolean enabled;
        private Set<String> roles;
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public Long getDomainId() { return domainId; }
        public void setDomainId(Long domainId) { this.domainId = domainId; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public Set<String> getRoles() { return roles; }
        public void setRoles(Set<String> roles) { this.roles = roles; }
    }

    public static class ResetPasswordRequest {
        private String password;
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class DatasetOwnerRequest {
        private Long userId;
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
    }

    public static class UserView {
        private final Long id;
        private final String username;
        private final String displayName;
        private final Long domainId;
        private final String domainCode;
        private final String domainName;
        private final boolean enabled;
        private final Set<String> roles;
        public UserView(AuthUserRecord user, Set<String> roles) {
            this.id = user.getUserId(); this.username = user.getUsername();
            this.displayName = user.getDisplayName(); this.domainId = user.getDomainId();
            this.domainCode = user.getDomainCode(); this.domainName = user.getDomainName();
            this.enabled = Boolean.TRUE.equals(user.getEnabled());
            this.roles = new LinkedHashSet<>(roles);
        }
        public Long getId() { return id; }
        public String getUsername() { return username; }
        public String getDisplayName() { return displayName; }
        public Long getDomainId() { return domainId; }
        public String getDomainCode() { return domainCode; }
        public String getDomainName() { return domainName; }
        public boolean isEnabled() { return enabled; }
        public Set<String> getRoles() { return roles; }
    }
}
