package org.example.service;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.mapper.NodeManagementMapper;
import org.springframework.stereotype.Service;

@Service
public class DatasetReplicaAvailabilityService {
    private final NodeManagementMapper nodeMapper;
    private final NodeAvailabilityService nodeAvailabilityService;

    public DatasetReplicaAvailabilityService(NodeManagementMapper nodeMapper,
                                             NodeAvailabilityService nodeAvailabilityService) {
        this.nodeMapper = nodeMapper;
        this.nodeAvailabilityService = nodeAvailabilityService;
    }

    public ReplicaAvailability evaluate(DatasetReplica replica) {
        if (replica == null) return unavailable("MISSING", "副本不存在");
        if (!"AVAILABLE".equals(replica.getAvailability())) {
            return unavailable(replica.getAvailability() == null ? "UNKNOWN" : replica.getAvailability(),
                    replica.getVerificationMessage() == null
                            ? "副本文件状态为 " + replica.getAvailability()
                            : replica.getVerificationMessage());
        }
        if (!"SHA-256".equalsIgnoreCase(replica.getChecksumAlgorithm())
                || replica.getChecksum() == null
                || !replica.getChecksum().trim().matches("(?i)[0-9a-f]{64}")
                || replica.getVerifiedAt() == null) {
            return unavailable("UNVERIFIED", "副本尚未完成 SHA-256 强校验");
        }
        NodeManagement node = nodeMapper.getNodeById(replica.getNodeId());
        NodeAvailability availability = nodeAvailabilityService.evaluate(node);
        if (!availability.isSchedulable()) {
            return unavailable("UNREACHABLE", availability.getReason());
        }
        return new ReplicaAvailability("USABLE", true, null);
    }

    public void enrich(DatasetReplica replica) {
        ReplicaAvailability availability = evaluate(replica);
        replica.setEffectiveAvailability(availability.getEffectiveAvailability());
        replica.setStatusReason(availability.getReason());
    }

    private ReplicaAvailability unavailable(String status, String reason) {
        return new ReplicaAvailability(status, false, reason);
    }
}
