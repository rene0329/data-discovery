package org.example.security.access;

import java.time.Instant;
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
     * One registered dataset as seen by the current user. The grant fields are
     * only filled when {@code basis} is GRANT.
     */
    public static class Item {
        private Long datasetId;
        private String name;
        private String datasetCode;
        private String version;
        private String status;
        private Long ownerDomainId;
        private String ownerDomainName;
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
        public Long getOwnerDomainId() { return ownerDomainId; }
        public void setOwnerDomainId(Long ownerDomainId) { this.ownerDomainId = ownerDomainId; }
        public String getOwnerDomainName() { return ownerDomainName; }
        public void setOwnerDomainName(String ownerDomainName) { this.ownerDomainName = ownerDomainName; }
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
