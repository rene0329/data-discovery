package org.example.security.access;

import java.time.LocalDateTime;

/** One row of dataset_access_grant. Timestamps are UTC wall-clock values. */
public class DatasetAccessGrant {
    private Long grantId;
    private Long userId;
    private Long datasetId;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public Long getGrantId() { return grantId; }
    public void setGrantId(Long grantId) { this.grantId = grantId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
