package org.example.dto.registration;

import java.util.List;

public class TaskPreflightResult {
    private final boolean valid;
    private final List<TaskPreflightCheck> checks;
    private final String executionMode;

    public TaskPreflightResult(List<TaskPreflightCheck> checks) {
        this(checks, null);
    }

    public TaskPreflightResult(List<TaskPreflightCheck> checks, String executionMode) {
        this.checks = checks;
        this.valid = checks.stream().allMatch(TaskPreflightCheck::isAvailable);
        this.executionMode = executionMode;
    }

    public boolean isValid() { return valid; }
    public List<TaskPreflightCheck> getChecks() { return checks; }
    public String getExecutionMode() { return executionMode; }
}
