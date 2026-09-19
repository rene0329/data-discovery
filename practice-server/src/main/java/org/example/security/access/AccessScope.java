package org.example.security.access;

public class AccessScope {
    private String datasetId;
    private String datasetVersion;
    private String path;
    private String action;
    private String targetNode;

    public AccessScope() {
    }

    public AccessScope(String datasetId, String datasetVersion, String path,
                       String action, String targetNode) {
        this.datasetId = datasetId;
        this.datasetVersion = datasetVersion;
        this.path = path;
        this.action = action;
        this.targetNode = targetNode;
    }

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
}
