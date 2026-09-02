package org.example.dto.registration;

import java.util.Map;

public class RegisterDatasetRequest {
    private Long candidateId;
    private String datasetCode;
    private String name;
    private String version;
    private String description;
    private String dataType;
    private String category;
    private String format;
    private String metadataJson;
    private Map<String, String> labels;
    private ResourceRequirements requiredResources;

    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }
    public String getDatasetCode() { return datasetCode; }
    public void setDatasetCode(String datasetCode) { this.datasetCode = datasetCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }
    public ResourceRequirements getRequiredResources() { return requiredResources; }
    public void setRequiredResources(ResourceRequirements requiredResources) { this.requiredResources = requiredResources; }
}
