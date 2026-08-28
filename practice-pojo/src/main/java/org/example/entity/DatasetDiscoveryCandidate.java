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
public class DatasetDiscoveryCandidate {
    private Long candidateId;
    private Integer nodeId;
    private String filePath;
    private String fileName;
    private String fileType;
    private Long sizeBytes;
    private String checksum;
    private LocalDateTime lastModifiedAt;
    private String availability;
    private Long registeredDatasetId;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
