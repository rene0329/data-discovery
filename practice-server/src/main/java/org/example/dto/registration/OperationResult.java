package org.example.dto.registration;

import java.util.Collections;
import java.util.List;

public class OperationResult {
    private boolean success;
    private String operationId;
    private String status;
    private String message;
    private Integer requestedCount;
    private Integer processedCount;
    private List<String> failedResources;

    public OperationResult() {
    }

    public OperationResult(boolean success, String operationId, String message) {
        this.success = success;
        this.operationId = operationId;
        this.status = success ? "COMPLETED" : "FAILED";
        this.message = message;
        this.failedResources = Collections.emptyList();
    }

    public static OperationResult completed(String message) {
        return new OperationResult(true, null, message);
    }

    public static OperationResult accepted(String operationId, String message) {
        return new OperationResult(true, operationId, message);
    }

    public static OperationResult discovery(String operationId, int requestedCount,
                                            int processedCount, List<String> failedResources) {
        OperationResult result = new OperationResult();
        result.operationId = operationId;
        result.requestedCount = requestedCount;
        result.processedCount = processedCount;
        result.failedResources = failedResources == null ? Collections.emptyList() : failedResources;
        if (requestedCount == 0 && !result.failedResources.isEmpty()) {
            result.success = false;
            result.status = "FAILED";
            result.message = "discovery failed before resources could be listed";
        } else if (requestedCount == 0) {
            result.success = false;
            result.status = "NO_ELIGIBLE_RESOURCE";
            result.message = "no eligible resource was found";
        } else if (processedCount == 0) {
            result.success = false;
            result.status = "FAILED";
            result.message = "no resource was processed";
        } else if (!result.failedResources.isEmpty()) {
            result.success = true;
            result.status = "PARTIAL_SUCCESS";
            result.message = "discovery completed with failures";
        } else {
            result.success = true;
            result.status = "COMPLETED";
            result.message = "discovery completed";
        }
        return result;
    }

    public boolean isSuccess() { return success; }
    public String getOperationId() { return operationId; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public Integer getRequestedCount() { return requestedCount; }
    public Integer getProcessedCount() { return processedCount; }
    public List<String> getFailedResources() { return failedResources; }

    public void setSuccess(boolean success) { this.success = success; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public void setStatus(String status) { this.status = status; }
    public void setMessage(String message) { this.message = message; }
    public void setRequestedCount(Integer requestedCount) { this.requestedCount = requestedCount; }
    public void setProcessedCount(Integer processedCount) { this.processedCount = processedCount; }
    public void setFailedResources(List<String> failedResources) { this.failedResources = failedResources; }
}
