package org.example.access;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetAccessEvent {
    private Long eventId;
    private String requestId;
    private String runId;
    private Integer taskId;
    private Long datasetId;
    private String datasetVersion;
    private Integer consumerNodeId;
    private Long sourceReplicaId;
    private Integer sourceNodeId;
    private String cacheLayer;
    private String routeReason;
    private Long expectedBytes;
    private Long bytesRead;
    private Long cacheHitBytes;
    private String checksum;
    private LocalDateTime startedAt;
    private LocalDateTime firstByteAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private Boolean success;
    private String failureReason;
    private LocalDateTime createdAt;
}
