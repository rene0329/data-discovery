package org.example.access;

import lombok.Data;

@Data
public class NodeReadOutcome {
    private String requestId;
    private String layer;
    private long bytesRead;
    private long cacheHitBytes;
    private long firstByteMs;
    private long durationMs;
    private String checksum;
    private boolean cacheComplete;
}
