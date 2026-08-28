package org.example.dto.registration;

public class TaskCreated {
    private final Integer taskId;
    private final String status;

    public TaskCreated(Integer taskId, String status) {
        this.taskId = taskId;
        this.status = status;
    }

    public Integer getTaskId() { return taskId; }
    public String getStatus() { return status; }
}
