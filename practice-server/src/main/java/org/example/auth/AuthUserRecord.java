package org.example.auth;

import java.time.LocalDateTime;

public class AuthUserRecord {
    private Long userId;
    private String username;
    private String passwordHash;
    private String displayName;
    private Long domainId;
    private String domainCode;
    private String domainName;
    private Boolean domainEnabled;
    private Boolean enabled;
    private Integer tokenVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Long getDomainId() { return domainId; }
    public void setDomainId(Long domainId) { this.domainId = domainId; }
    public String getDomainCode() { return domainCode; }
    public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }
    public Boolean getDomainEnabled() { return domainEnabled; }
    public void setDomainEnabled(Boolean domainEnabled) { this.domainEnabled = domainEnabled; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(Integer tokenVersion) { this.tokenVersion = tokenVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
