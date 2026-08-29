package org.example.dto.registration;

public class TaskCreated {
    private Integer taskId;
    private String status;

    public TaskCreated() {
    }

    public TaskCreated(Integer taskId, String status) {
        this.taskId = taskId;
        this.status = status;
    }

    public Integer getTaskId() { return taskId; }
    public String getStatus() { return status; }
    public void setTaskId(Integer taskId) { this.taskId = taskId; }
    public void setStatus(String status) { this.status = status; }
}
