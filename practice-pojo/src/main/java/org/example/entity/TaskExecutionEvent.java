package org.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionEvent {
    private Long eventId;
    private Integer taskId;
    private Long datasetId;
    private String executionMode;
    private String eventType;
    private LocalDateTime occurredAt;
    private String jobName;
    private String podName;
    private String nodeName;
    private String inputPath;
    private Long bytesProcessed;
    private String checksumSha256;
    private Long durationMs;
    private String outputSummary;
    private String outputChecksumSha256;
    private Integer attempt;
    private String detailsJson;
}
