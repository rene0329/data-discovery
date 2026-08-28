package org.example.dto.registration;

public class OperationResult {
    private final boolean success;
    private final String operationId;
    private final String message;

    public OperationResult(boolean success, String operationId, String message) {
        this.success = success;
        this.operationId = operationId;
        this.message = message;
    }

    public static OperationResult completed(String message) {
        return new OperationResult(true, null, message);
    }

    public static OperationResult accepted(String operationId, String message) {
        return new OperationResult(true, operationId, message);
    }

    public boolean isSuccess() { return success; }
    public String getOperationId() { return operationId; }
    public String getMessage() { return message; }
}
