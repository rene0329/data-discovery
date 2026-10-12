package org.example.dto.admin;

import org.example.entity.DataTransferRateLimitConfiguration;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class DataTransferRateLimitDtos {
    private DataTransferRateLimitDtos() {}

    public static class UpdateRequest {
        private Long version;
        private Boolean enabled;
        private Boolean adaptiveEnabled;
        private Boolean bandwidthProbeEnabled;
        private String defaultRate;
        private String pathRules;
        private BigDecimal targetMin;
        private BigDecimal targetMax;
        private BigDecimal adaptiveSafetyFactor;
        private Integer measurementMaxAgeSeconds;
        private String adaptiveCentralNode;

        public Long getVersion() { return version; }
        public void setVersion(Long version) { this.version = version; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public Boolean getAdaptiveEnabled() { return adaptiveEnabled; }
        public void setAdaptiveEnabled(Boolean adaptiveEnabled) { this.adaptiveEnabled = adaptiveEnabled; }
        public Boolean getBandwidthProbeEnabled() { return bandwidthProbeEnabled; }
        public void setBandwidthProbeEnabled(Boolean bandwidthProbeEnabled) { this.bandwidthProbeEnabled = bandwidthProbeEnabled; }
        public String getDefaultRate() { return defaultRate; }
        public void setDefaultRate(String defaultRate) { this.defaultRate = defaultRate; }
        public String getPathRules() { return pathRules; }
        public void setPathRules(String pathRules) { this.pathRules = pathRules; }
        public BigDecimal getTargetMin() { return targetMin; }
        public void setTargetMin(BigDecimal targetMin) { this.targetMin = targetMin; }
        public BigDecimal getTargetMax() { return targetMax; }
        public void setTargetMax(BigDecimal targetMax) { this.targetMax = targetMax; }
        public BigDecimal getAdaptiveSafetyFactor() { return adaptiveSafetyFactor; }
        public void setAdaptiveSafetyFactor(BigDecimal adaptiveSafetyFactor) { this.adaptiveSafetyFactor = adaptiveSafetyFactor; }
        public Integer getMeasurementMaxAgeSeconds() { return measurementMaxAgeSeconds; }
        public void setMeasurementMaxAgeSeconds(Integer measurementMaxAgeSeconds) { this.measurementMaxAgeSeconds = measurementMaxAgeSeconds; }
        public String getAdaptiveCentralNode() { return adaptiveCentralNode; }
        public void setAdaptiveCentralNode(String adaptiveCentralNode) { this.adaptiveCentralNode = adaptiveCentralNode; }
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
        private final Boolean adaptiveEnabled;
        private final Boolean bandwidthProbeEnabled;
        private final String defaultRate;
        private final String pathRules;
        private final BigDecimal targetMin;
        private final BigDecimal targetMax;
        private final BigDecimal adaptiveSafetyFactor;
        private final Integer measurementMaxAgeSeconds;
        private final String adaptiveCentralNode;
        private final String updatedBy;
        private final LocalDateTime updatedAt;

        public View(DataTransferRateLimitConfiguration value) {
            this.version = value.getVersion();
            this.enabled = value.getEnabled();
            this.adaptiveEnabled = value.getAdaptiveEnabled();
            this.bandwidthProbeEnabled = value.getBandwidthProbeEnabled();
            this.defaultRate = value.getDefaultRate();
            this.pathRules = value.getPathRules();
            this.targetMin = value.getTargetMin();
            this.targetMax = value.getTargetMax();
            this.adaptiveSafetyFactor = value.getAdaptiveSafetyFactor();
            this.measurementMaxAgeSeconds = value.getMeasurementMaxAgeSeconds();
            this.adaptiveCentralNode = value.getAdaptiveCentralNode();
            this.updatedBy = value.getUpdatedBy();
            this.updatedAt = value.getUpdatedAt();
        }

        public Long getVersion() { return version; }
        public Boolean getEnabled() { return enabled; }
        public Boolean getAdaptiveEnabled() { return adaptiveEnabled; }
        public Boolean getBandwidthProbeEnabled() { return bandwidthProbeEnabled; }
        public String getDefaultRate() { return defaultRate; }
        public String getPathRules() { return pathRules; }
        public BigDecimal getTargetMin() { return targetMin; }
        public BigDecimal getTargetMax() { return targetMax; }
        public BigDecimal getAdaptiveSafetyFactor() { return adaptiveSafetyFactor; }
        public Integer getMeasurementMaxAgeSeconds() { return measurementMaxAgeSeconds; }
        public String getAdaptiveCentralNode() { return adaptiveCentralNode; }
        public String getUpdatedBy() { return updatedBy; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
    }

    public static class Preview {
        private final String sourceNode;
        private final String targetNode;
        private final String rate;
        private final String source;
        private final Long configVersion;
        private final Long basisBandwidthMbps;
        private final BigDecimal targetRatio;

        public Preview(String sourceNode, String targetNode, String rate, String source,
                       Long configVersion, Long basisBandwidthMbps, BigDecimal targetRatio) {
            this.sourceNode = sourceNode;
            this.targetNode = targetNode;
            this.rate = rate;
            this.source = source;
            this.configVersion = configVersion;
            this.basisBandwidthMbps = basisBandwidthMbps;
            this.targetRatio = targetRatio;
        }

        public String getSourceNode() { return sourceNode; }
        public String getTargetNode() { return targetNode; }
        public String getRate() { return rate; }
        public String getSource() { return source; }
        public Long getConfigVersion() { return configVersion; }
        public Long getBasisBandwidthMbps() { return basisBandwidthMbps; }
        public BigDecimal getTargetRatio() { return targetRatio; }
    }
}
