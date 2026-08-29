package org.example.dto.registration;

public class TaskPreflightCheck {
    private final String resourceType;
    private final String resourceId;
    private final String name;
    private final boolean available;
    private final String status;
    private final String errorCode;
    private final String message;

    public TaskPreflightCheck(String resourceType, String resourceId, String name,
                              boolean available, String status, String errorCode, String message) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.name = name;
        this.available = available;
        this.status = status;
        this.errorCode = errorCode;
        this.message = message;
    }

    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getName() { return name; }
    public boolean isAvailable() { return available; }
    public String getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
}
