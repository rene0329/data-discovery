package org.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisteredDataset {
    private Long datasetId;
    private Integer legacyDataId;
    private String datasetCode;
    private String name;
    private String datasetVersion;
    private String description;
    private String dataType;
    private String labelsJson;
    private Double requiredCpu;
    private Double requiredMemoryGi;
    private Double requiredGpu;
    private String status;
    private Long defaultRuntimeImageId;
    private LocalDateTime verifiedAt;
    private String verificationMessage;
    private Integer rowVersion;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<DatasetReplica> replicas;
}
