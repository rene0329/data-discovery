package org.example.access;

import lombok.Data;

@Data
public class DatasetAccessRequest {
    private String requestId;
    private String runId;
    private Integer taskId;
    private Integer consumerNodeId;
    private boolean clearCache;
}
