package org.example.dto.registration;

import java.util.Map;

public class UpdateDatasetRequest {
    private String name;
    private String description;
    private String dataType;
    private Map<String, String> labels;
    private ResourceRequirements requiredResources;
    private Integer rowVersion;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }
    public ResourceRequirements getRequiredResources() { return requiredResources; }
    public void setRequiredResources(ResourceRequirements requiredResources) { this.requiredResources = requiredResources; }
    public Integer getRowVersion() { return rowVersion; }
    public void setRowVersion(Integer rowVersion) { this.rowVersion = rowVersion; }
}
