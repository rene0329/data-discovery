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
public class SchedulingAssignment {
    private Long assignmentId;
    private Long planId;
    private Long datasetId;
    private Long replicaId;
    private Integer sourceNodeId;
    private Integer targetNodeId;
    private String action;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
