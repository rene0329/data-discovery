package org.example.dto.registration;

import org.example.entity.TaskExecutionEvent;
import org.example.entity.TaskManagement;

import java.util.List;

public class TaskExecutionDetail {
    private final TaskManagement task;
    private final List<TaskExecutionEvent> events;

    public TaskExecutionDetail(TaskManagement task, List<TaskExecutionEvent> events) {
        this.task = task;
        this.events = events;
    }

    public TaskManagement getTask() { return task; }
    public List<TaskExecutionEvent> getEvents() { return events; }
}
