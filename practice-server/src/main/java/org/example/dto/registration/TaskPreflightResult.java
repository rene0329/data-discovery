package org.example.dto.registration;

import java.util.List;

public class TaskPreflightResult {
    private final boolean valid;
    private final List<TaskPreflightCheck> checks;

    public TaskPreflightResult(List<TaskPreflightCheck> checks) {
        this.checks = checks;
        this.valid = checks.stream().allMatch(TaskPreflightCheck::isAvailable);
    }

    public boolean isValid() { return valid; }
    public List<TaskPreflightCheck> getChecks() { return checks; }
}
