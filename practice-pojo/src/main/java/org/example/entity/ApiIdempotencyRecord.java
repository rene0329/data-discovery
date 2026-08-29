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
public class ApiIdempotencyRecord {
    private Long id;
    private String idempotencyKey;
    private String resourceType;
    private String action;
    private String requestHash;
    private String resourceId;
    private String responseJson;
    private String executionStatus;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
