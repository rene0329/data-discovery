package org.example.security.access;

public class NodeAccessTokenClaims {
    private String subject;
    private String datasetId;
    private String datasetVersion;
    private String path;
    private String action;
    private String targetNode;
    private long issuedAtEpochSeconds;
    private long expiresAtEpochSeconds;
    private String jti;

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetNode() { return targetNode; }
    public void setTargetNode(String targetNode) { this.targetNode = targetNode; }
    public long getIssuedAtEpochSeconds() { return issuedAtEpochSeconds; }
    public void setIssuedAtEpochSeconds(long issuedAtEpochSeconds) { this.issuedAtEpochSeconds = issuedAtEpochSeconds; }
    public long getExpiresAtEpochSeconds() { return expiresAtEpochSeconds; }
    public void setExpiresAtEpochSeconds(long expiresAtEpochSeconds) { this.expiresAtEpochSeconds = expiresAtEpochSeconds; }
    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
}
