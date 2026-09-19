package org.example.dto.registration;

public class TaskCreated {
    private Integer taskId;
    private String status;
    private String executionMode;
    private String acceptanceRunId;
    private Integer runRound;

    public TaskCreated() {
    }

    public TaskCreated(Integer taskId, String status) {
        this.taskId = taskId;
        this.status = status;
    }

    public TaskCreated(Integer taskId, String status, String executionMode,
                       String acceptanceRunId, Integer runRound) {
        this.taskId = taskId;
        this.status = status;
        this.executionMode = executionMode;
        this.acceptanceRunId = acceptanceRunId;
        this.runRound = runRound;
    }

    public Integer getTaskId() { return taskId; }
    public String getStatus() { return status; }
    public void setTaskId(Integer taskId) { this.taskId = taskId; }
    public void setStatus(String status) { this.status = status; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public String getAcceptanceRunId() { return acceptanceRunId; }
    public void setAcceptanceRunId(String acceptanceRunId) { this.acceptanceRunId = acceptanceRunId; }
    public Integer getRunRound() { return runRound; }
    public void setRunRound(Integer runRound) { this.runRound = runRound; }
}
