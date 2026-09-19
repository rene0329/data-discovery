package org.example.security.access;

public class AccessAuditContext {
    private final String requestId;
    private final String runId;
    private final String clientIp;

    public AccessAuditContext(String requestId, String runId, String clientIp) {
        this.requestId = requestId;
        this.runId = runId;
        this.clientIp = clientIp;
    }

    public String getRequestId() { return requestId; }
    public String getRunId() { return runId; }
    public String getClientIp() { return clientIp; }
}
