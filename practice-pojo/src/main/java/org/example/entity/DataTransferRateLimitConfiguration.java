package org.example.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class DataTransferRateLimitConfiguration {
    private Integer configId;
    private Long historyId;
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
    private String updatedBy;
    private LocalDateTime updatedAt;

    public Integer getConfigId() { return configId; }
    public void setConfigId(Integer configId) { this.configId = configId; }
    public Long getHistoryId() { return historyId; }
    public void setHistoryId(Long historyId) { this.historyId = historyId; }
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
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
