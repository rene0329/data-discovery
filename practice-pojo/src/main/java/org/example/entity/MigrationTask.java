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
public class MigrationTask {
    private Long id;
    private Integer taskId;
    private Integer dataId;
    private Integer sourceNodeId;
    private Integer targetNodeId;
    private String status;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String checksumBefore;
    private String checksumAfter;
    private Long bytesTotal;
    private Long bytesDone;
}
