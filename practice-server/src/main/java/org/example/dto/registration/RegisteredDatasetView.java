package org.example.dto.registration;

import org.example.entity.DatasetReplica;
import org.example.entity.RegisteredDataset;

import java.util.ArrayList;
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
    /** Enabled domains the dataset is located in (DatasetDomainMapper), ordered by domain id. */
    private List<Long> domainIds = new ArrayList<>();
    /** Names of {@link #domainIds}, in the same order. */
    private List<String> domainNames = new ArrayList<>();
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
    private String authoritativeSha256;
    private Long authoritativeSizeBytes;
    private Object schema;
    private String schemaDigest;

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
    public List<Long> getDomainIds() { return domainIds; }
    public List<String> getDomainNames() { return domainNames; }
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
    public String getAuthoritativeSha256() { return authoritativeSha256; }
    public Long getAuthoritativeSizeBytes() { return authoritativeSizeBytes; }
    public Object getSchema() { return schema; }
    public String getSchemaDigest() { return schemaDigest; }

    /** Replaces the location domains; null lists become empty so the JSON never carries null. */
    public void setLocationDomains(List<Long> domainIds, List<String> domainNames) {
        this.domainIds = domainIds == null ? new ArrayList<>() : new ArrayList<>(domainIds);
        this.domainNames = domainNames == null ? new ArrayList<>() : new ArrayList<>(domainNames);
    }

    public void setReplicaHealth(String healthStatus, int availableReplicaCount,
                                 int totalReplicaCount, String statusReason) {
        this.healthStatus = healthStatus;
        this.availableReplicaCount = availableReplicaCount;
        this.totalReplicaCount = totalReplicaCount;
        this.statusReason = statusReason;
    }

    public void setVersionAuthority(String authoritativeSha256, Long authoritativeSizeBytes,
                                    Object schema, String schemaDigest) {
        this.authoritativeSha256 = authoritativeSha256;
        this.authoritativeSizeBytes = authoritativeSizeBytes;
        this.schema = schema;
        this.schemaDigest = schemaDigest;
    }
}
