package org.example.dto.registration;

import org.example.entity.DatasetReplica;
import org.example.entity.RegisteredDataset;

import java.util.List;
import java.util.Map;

public class RegisteredDatasetView {
    private Long datasetId;
    private String datasetCode;
    private String name;
    private String version;
    private String description;
    private String dataType;
    private String category;
    private String format;
    private String status;
    private Double dataHeat;
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private java.time.LocalDateTime heatUpdatedAt;
    private Map<String, String> labels;
    private ResourceRequirements requiredResources;
    private Long defaultRuntimeImageId;
    private List<DatasetReplica> replicas;
    private String healthStatus;
    private Integer availableReplicaCount;
    private Integer totalReplicaCount;
    private String statusReason;
    private Integer rowVersion;

    public static RegisteredDatasetView from(RegisteredDataset entity, Map<String, String> labels,
                                             List<DatasetReplica> replicas) {
        RegisteredDatasetView view = new RegisteredDatasetView();
        view.datasetId = entity.getDatasetId();
        view.datasetCode = entity.getDatasetCode();
        view.name = entity.getName();
        view.version = entity.getDatasetVersion();
        view.description = entity.getDescription();
        view.dataType = entity.getDataType();
        view.category = entity.getCategory();
        view.format = entity.getDataFormat();
        view.status = entity.getStatus();
        view.dataHeat = entity.getDataHeat();
        view.heatUpdatedAt = entity.getHeatUpdatedAt();
        view.labels = labels;
        ResourceRequirements resources = new ResourceRequirements();
        resources.setCpu(entity.getRequiredCpu());
        resources.setMemoryGi(entity.getRequiredMemoryGi());
        resources.setGpu(entity.getRequiredGpu());
        view.requiredResources = resources;
        view.defaultRuntimeImageId = entity.getDefaultRuntimeImageId();
        view.replicas = replicas;
        view.rowVersion = entity.getRowVersion();
        return view;
    }

    public Long getDatasetId() { return datasetId; }
    public String getDatasetCode() { return datasetCode; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getDescription() { return description; }
    public String getDataType() { return dataType; }
    public String getCategory() { return category; }
    public String getFormat() { return format; }
    public String getStatus() { return status; }
    public Double getDataHeat() { return dataHeat; }
    public java.time.LocalDateTime getHeatUpdatedAt() { return heatUpdatedAt; }
    public Map<String, String> getLabels() { return labels; }
    public ResourceRequirements getRequiredResources() { return requiredResources; }
    public Long getDefaultRuntimeImageId() { return defaultRuntimeImageId; }
    public List<DatasetReplica> getReplicas() { return replicas; }
    public Integer getRowVersion() { return rowVersion; }
    public String getHealthStatus() { return healthStatus; }
    public Integer getAvailableReplicaCount() { return availableReplicaCount; }
    public Integer getTotalReplicaCount() { return totalReplicaCount; }
    public String getStatusReason() { return statusReason; }

    public void setReplicaHealth(String healthStatus, int availableReplicaCount,
                                 int totalReplicaCount, String statusReason) {
        this.healthStatus = healthStatus;
        this.availableReplicaCount = availableReplicaCount;
        this.totalReplicaCount = totalReplicaCount;
        this.statusReason = statusReason;
    }
}
