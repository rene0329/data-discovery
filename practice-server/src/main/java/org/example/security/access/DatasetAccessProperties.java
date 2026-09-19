package org.example.security.access;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.dataset-access")
public class DatasetAccessProperties {
    private boolean enabled = true;
    private long tokenTtlSeconds = 300;
    private String hmacSecret = "topic4-acceptance-hmac-secret-change-before-production";
    private Map<String, String> credentials = new LinkedHashMap<>();
    private Map<String, List<String>> permissions = new LinkedHashMap<>();

    public DatasetAccessProperties() {
        credentials.put("reviewer-a", "topic4-reviewer-a-secret");
        credentials.put("reviewer-b", "topic4-reviewer-b-secret");
        permissions.put("reviewer-a", new ArrayList<>(Arrays.asList("READ:*", "VERIFY:*")));
        permissions.put("reviewer-b", new ArrayList<>(Arrays.asList("VERIFY:*")));
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getTokenTtlSeconds() { return tokenTtlSeconds; }
    public void setTokenTtlSeconds(long tokenTtlSeconds) { this.tokenTtlSeconds = tokenTtlSeconds; }
    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    public Map<String, String> getCredentials() { return credentials; }
    public void setCredentials(Map<String, String> credentials) { this.credentials = credentials; }
    public Map<String, List<String>> getPermissions() { return permissions; }
    public void setPermissions(Map<String, List<String>> permissions) { this.permissions = permissions; }
}
