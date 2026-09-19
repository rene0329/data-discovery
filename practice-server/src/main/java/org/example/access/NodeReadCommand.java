package org.example.access;

import lombok.Data;

@Data
public class NodeReadCommand {
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
