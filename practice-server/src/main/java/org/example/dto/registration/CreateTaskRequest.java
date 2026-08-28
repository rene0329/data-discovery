package org.example.dto.registration;

import java.util.List;

public class CreateTaskRequest {
    private String taskName;
    private List<Long> datasetIds;
    private Long runtimeImageId;
    private ResourceRequirements resourceOverrides;

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public List<Long> getDatasetIds() { return datasetIds; }
    public void setDatasetIds(List<Long> datasetIds) { this.datasetIds = datasetIds; }
    public Long getRuntimeImageId() { return runtimeImageId; }
    public void setRuntimeImageId(Long runtimeImageId) { this.runtimeImageId = runtimeImageId; }
    public ResourceRequirements getResourceOverrides() { return resourceOverrides; }
    public void setResourceOverrides(ResourceRequirements resourceOverrides) { this.resourceOverrides = resourceOverrides; }
}
