package org.example.dto.registration;

public class TaskPreflightCheck {
    private final String resourceType;
    private final String resourceId;
    private final String name;
    private final boolean available;
    private final String status;
    private final String errorCode;
    private final String message;
    /** CENTRALIZED or IN_PLACE for mode-specific checks; null for checks that apply to every mode. */
    private final String executionMode;

    public TaskPreflightCheck(String resourceType, String resourceId, String name,
                              boolean available, String status, String errorCode, String message) {
        this(resourceType, resourceId, name, available, status, errorCode, message, null);
    }

    public TaskPreflightCheck(String resourceType, String resourceId, String name,
                              boolean available, String status, String errorCode, String message,
                              String executionMode) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.name = name;
        this.available = available;
        this.status = status;
        this.errorCode = errorCode;
        this.message = message;
        this.executionMode = executionMode;
    }

    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getName() { return name; }
    public boolean isAvailable() { return available; }
    public String getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public String getExecutionMode() { return executionMode; }
}
