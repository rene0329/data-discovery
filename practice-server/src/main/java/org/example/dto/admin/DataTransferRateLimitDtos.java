package org.example.dto.admin;

import org.example.entity.DataTransferRateLimitConfiguration;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class DataTransferRateLimitDtos {
    private DataTransferRateLimitDtos() {}

    public static class UpdateRequest {
        private Long version;
        private Boolean enabled;
        private String defaultRate;
        private String pathRules;
        private BigDecimal targetMin;
        private BigDecimal targetMax;

        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public String getDefaultRate() { return defaultRate; }
        public void setDefaultRate(String defaultRate) { this.defaultRate = defaultRate; }
        public String getPathRules() { return pathRules; }
        public void setPathRules(String pathRules) { this.pathRules = pathRules; }
        public BigDecimal getTargetMin() { return targetMin; }
        public void setTargetMin(BigDecimal targetMin) { this.targetMin = targetMin; }
        public BigDecimal getTargetMax() { return targetMax; }
        public void setTargetMax(BigDecimal targetMax) { this.targetMax = targetMax; }
    }

    public static class RollbackRequest {
        private Long version;
        private Long restoreVersion;

        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public Long getRestoreVersion() { return restoreVersion; }
        public void setRestoreVersion(Long restoreVersion) { this.restoreVersion = restoreVersion; }
    }

    public static class View {
        private final Long version;
        private final Boolean enabled;
        private final String defaultRate;
        private final String pathRules;
        private final BigDecimal targetMin;
        private final BigDecimal targetMax;
        private final String updatedBy;
        private final LocalDateTime updatedAt;

        public View(DataTransferRateLimitConfiguration value) {
            this.version = value.getVersion();
            this.enabled = value.getEnabled();
            this.defaultRate = value.getDefaultRate();
            this.pathRules = value.getPathRules();
            this.targetMin = value.getTargetMin();
            this.targetMax = value.getTargetMax();
            this.updatedBy = value.getUpdatedBy();
            this.updatedAt = value.getUpdatedAt();
        }

        public Long getVersion() { return version; }
        public Boolean getEnabled() { return enabled; }
        public String getDefaultRate() { return defaultRate; }
        public String getPathRules() { return pathRules; }
        public BigDecimal getTargetMin() { return targetMin; }
        public BigDecimal getTargetMax() { return targetMax; }
        public String getUpdatedBy() { return updatedBy; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
    }
}
