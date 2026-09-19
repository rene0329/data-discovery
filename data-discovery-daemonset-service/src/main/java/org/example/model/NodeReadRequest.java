package org.example.model;

import lombok.Data;

@Data
public class NodeReadRequest {
    private String requestId;
    private String datasetId;
    private String datasetVersion;
    private String sourcePath;
    private String cacheKey;
    private String localPath;
    private String sourceUrl;
    private String sourceToken;
    private Long expectedSize;
    private String expectedChecksum;
    private boolean clearCache;
}
