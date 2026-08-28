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
public class NodeDiscoveryCandidate {
    private Long candidateId;
    private String clusterId;
    private String k8sUid;
    private String k8sNodeName;
    private String internalIp;
    private String externalIp;
    private String observedRole;
    private Double maxCpu;
    private Double maxMemory;
    private Double currentCpu;
    private Double currentMemory;
    private String observedStatus;
    private Integer registeredNodeId;
    private String labelsJson;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
