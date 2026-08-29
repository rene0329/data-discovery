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
    private String status;
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
        view.status = entity.getStatus();
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
    public String getStatus() { return status; }
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
