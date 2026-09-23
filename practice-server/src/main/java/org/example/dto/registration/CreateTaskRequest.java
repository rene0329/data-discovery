package org.example.dto.registration;

import java.util.List;

public class CreateTaskRequest {
    private String taskName;
    private List<Long> datasetIds;
    private Long runtimeImageId;
    private ResourceRequirements resourceOverrides;
    /**
     * CENTRALIZED, IN_PLACE or COMPARISON. COMPARISON runs every dataset in both modes under
     * one task ID. Missing values keep old clients working and default to IN_PLACE.
     */
    private String executionMode;
    /** Groups independent centralized/in-place runs that use the same acceptance input. */
    private String acceptanceRunId;
    /** Repeat number within an acceptance run, starting at one. */
    private Integer runRound;

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public List<Long> getDatasetIds() { return datasetIds; }
    public void setDatasetIds(List<Long> datasetIds) { this.datasetIds = datasetIds; }
    public Long getRuntimeImageId() { return runtimeImageId; }
    public void setRuntimeImageId(Long runtimeImageId) { this.runtimeImageId = runtimeImageId; }
    public ResourceRequirements getResourceOverrides() { return resourceOverrides; }
    public void setResourceOverrides(ResourceRequirements resourceOverrides) { this.resourceOverrides = resourceOverrides; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public String getAcceptanceRunId() { return acceptanceRunId; }
    public void setAcceptanceRunId(String acceptanceRunId) { this.acceptanceRunId = acceptanceRunId; }
    public Integer getRunRound() { return runRound; }
    public void setRunRound(Integer runRound) { this.runRound = runRound; }
}
