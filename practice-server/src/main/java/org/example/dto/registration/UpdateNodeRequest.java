package org.example.dto.registration;

import java.util.Map;

public class UpdateNodeRequest {
    private String displayName;
    private String role;
    private Map<String, String> labels;
    private Integer version;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
