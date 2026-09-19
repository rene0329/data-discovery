package org.example.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NodeReadResult {
    private String requestId;
    private String layer;
    private long bytesRead;
    private long cacheHitBytes;
    private long firstByteMs;
    private long durationMs;
    private String checksum;
    private boolean cacheComplete;
}
