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
public class DatasetReplica {
    private Long replicaId;
    private Long datasetId;
    private Integer nodeId;
    private String filePath;
    private Long sizeBytes;
    private String checksum;
    private String availability;
    private LocalDateTime lastSeenAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
