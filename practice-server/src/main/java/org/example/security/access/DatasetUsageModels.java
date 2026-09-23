package org.example.security.access;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Request and response bodies of /api/v1/security/dataset-access. */
public final class DatasetUsageModels {
    private DatasetUsageModels() {
    }

    /** GET /api/v1/security/dataset-access. */
    public static class Overview {
        /** The instant every item was evaluated at; count grant expiry down from here. */
        private Instant serverTime;
        private long ttlMinutes;
        private List<Item> items;

        public Overview() {
        }

        public Overview(Instant serverTime, long ttlMinutes, List<Item> items) {
            this.serverTime = serverTime;
            this.ttlMinutes = ttlMinutes;
            this.items = items;
        }

        public Instant getServerTime() { return serverTime; }
        public void setServerTime(Instant serverTime) { this.serverTime = serverTime; }
        public long getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(long ttlMinutes) { this.ttlMinutes = ttlMinutes; }
        public List<Item> getItems() { return items; }
        public void setItems(List<Item> items) { this.items = items; }
    }

    /**
     * One registered dataset as seen by the current user. {@code domainIds} /
     * {@code domainNames} (same order, by domain id; empty when the dataset is in
     * no enabled domain) are where its replicas currently are. The grant fields
     * are only filled when {@code basis} is GRANT.
     */
    public static class Item {
        private Long datasetId;
        private String name;
        private String datasetCode;
        private String version;
        private String status;
        private List<Long> domainIds = new ArrayList<>();
        private List<String> domainNames = new ArrayList<>();
        private boolean accessible;
        private String basis;
        private Long grantId;
        private Instant grantExpiresAt;
        private String grantReason;

        public Long getDatasetId() { return datasetId; }
        public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDatasetCode() { return datasetCode; }
        public void setDatasetCode(String datasetCode) { this.datasetCode = datasetCode; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<Long> getDomainIds() { return domainIds; }
        public void setDomainIds(List<Long> domainIds) {
            this.domainIds = domainIds == null ? new ArrayList<>() : domainIds;
        }
        public List<String> getDomainNames() { return domainNames; }
        public void setDomainNames(List<String> domainNames) {
            this.domainNames = domainNames == null ? new ArrayList<>() : domainNames;
        }
        public boolean isAccessible() { return accessible; }
        public void setAccessible(boolean accessible) { this.accessible = accessible; }
        public String getBasis() { return basis; }
        public void setBasis(String basis) { this.basis = basis; }
        public Long getGrantId() { return grantId; }
        public void setGrantId(Long grantId) { this.grantId = grantId; }
        public Instant getGrantExpiresAt() { return grantExpiresAt; }
        public void setGrantExpiresAt(Instant grantExpiresAt) { this.grantExpiresAt = grantExpiresAt; }
        public String getGrantReason() { return grantReason; }
        public void setGrantReason(String grantReason) { this.grantReason = grantReason; }
    }

    /** POST /api/v1/security/dataset-access/grants request body. */
    public static class GrantRequest {
        private Long datasetId;
        private String reason;

        public GrantRequest() {
        }

        public GrantRequest(Long datasetId, String reason) {
            this.datasetId = datasetId;
            this.reason = reason;
        }

        public Long getDatasetId() { return datasetId; }
        public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /** GET /api/v1/security/dataset-access/grants (ADMIN only): the 访问申请日志. */
    public static class GrantLog {
        /** The instant {@code active} was evaluated at. */
        private Instant serverTime;
        private List<GrantLogItem> items;

        public GrantLog() {
        }

        public GrantLog(Instant serverTime, List<GrantLogItem> items) {
            this.serverTime = serverTime;
            this.items = items;
        }

        public Instant getServerTime() { return serverTime; }
        public void setServerTime(Instant serverTime) { this.serverTime = serverTime; }
        public List<GrantLogItem> getItems() { return items; }
        public void setItems(List<GrantLogItem> items) { this.items = items; }
    }

    /** One grant: who applied, for which dataset, why, when, and whether it is still usable. */
    public static class GrantLogItem {
        private Long grantId;
        private Long userId;
        private String username;
        private String displayName;
        private String domainName;
        private Long datasetId;
        private String datasetName;
        private String datasetCode;
        private String datasetVersion;
        private String reason;
        private Instant createdAt;
        private Instant expiresAt;
        private boolean active;

        public Long getGrantId() { return grantId; }
        public void setGrantId(Long grantId) { this.grantId = grantId; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getDomainName() { return domainName; }
        public void setDomainName(String domainName) { this.domainName = domainName; }
        public Long getDatasetId() { return datasetId; }
        public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
        public String getDatasetName() { return datasetName; }
        public void setDatasetName(String datasetName) { this.datasetName = datasetName; }
        public String getDatasetCode() { return datasetCode; }
        public void setDatasetCode(String datasetCode) { this.datasetCode = datasetCode; }
        public String getDatasetVersion() { return datasetVersion; }
        public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    /** POST /api/v1/security/dataset-access/grants response body. */
    public static class GrantIssued {
        private Long grantId;
        private Long datasetId;
        private Instant expiresAt;
        private long ttlMinutes;

        public GrantIssued() {
        }

        public GrantIssued(Long grantId, Long datasetId, Instant expiresAt, long ttlMinutes) {
            this.grantId = grantId;
            this.datasetId = datasetId;
            this.expiresAt = expiresAt;
            this.ttlMinutes = ttlMinutes;
        }

        public Long getGrantId() { return grantId; }
        public void setGrantId(Long grantId) { this.grantId = grantId; }
        public Long getDatasetId() { return datasetId; }
        public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public long getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(long ttlMinutes) { this.ttlMinutes = ttlMinutes; }
    }
}
