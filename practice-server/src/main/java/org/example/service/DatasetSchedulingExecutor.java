package org.example.service;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.SchedulingAssignment;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Data-only scheduling: no runtime images, compute tasks or Kubernetes Jobs. */
@Service
public class DatasetSchedulingExecutor {
    private final DatasetRegistrationMapper datasetMapper;
    private final NodeManagementMapper nodeMapper;
    private final SchedulingPlanMapper planMapper;
    private final DatasetReplicaAvailabilityService replicaAvailability;
    private final NodeAvailabilityService nodeAvailability;
    private final NetworkTopologyService topology;
    private final DatasetUploadClient transfer;

    public DatasetSchedulingExecutor(DatasetRegistrationMapper datasetMapper,
                                     NodeManagementMapper nodeMapper,
                                     SchedulingPlanMapper planMapper,
                                     DatasetReplicaAvailabilityService replicaAvailability,
                                     NodeAvailabilityService nodeAvailability,
                                     NetworkTopologyService topology,
                                     DatasetUploadClient transfer) {
        this.datasetMapper = datasetMapper;
        this.nodeMapper = nodeMapper;
        this.planMapper = planMapper;
        this.replicaAvailability = replicaAvailability;
        this.nodeAvailability = nodeAvailability;
        this.topology = topology;
        this.transfer = transfer;
    }

    @Async
    public void execute(Long planId, List<SchedulingAssignment> assignments) {
        planMapper.updatePlanStatus(planId, "RUNNING", null);
        int completed = 0;
        String failure = null;
        for (SchedulingAssignment assignment : assignments) {
            planMapper.updateAssignmentStatus(assignment.getAssignmentId(), "RUNNING", null);
            try {
                transfer(assignment);
                planMapper.updateAssignmentStatus(assignment.getAssignmentId(), "COMPLETED", null);
                completed++;
            } catch (Exception error) {
                failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                planMapper.updateAssignmentStatus(assignment.getAssignmentId(), "FAILED", failure);
            }
        }
        String status = completed == assignments.size() ? "COMPLETED"
                : completed == 0 ? "FAILED" : "PARTIAL_COMPLETED";
        planMapper.updatePlanStatus(planId, status, failure);
    }

    private void transfer(SchedulingAssignment assignment) {
        if (!"COPY".equals(assignment.getAction()) && !"MOVE".equals(assignment.getAction())) {
            throw RegistrationException.invalid("unsupported data transfer action");
        }
        RegisteredDataset dataset = datasetMapper.findDatasetById(assignment.getDatasetId());
        DatasetReplica replica = datasetMapper.findReplicaById(assignment.getReplicaId());
        if (dataset == null || !"ACTIVE".equals(dataset.getStatus()) || replica == null
                || !dataset.getDatasetId().equals(replica.getDatasetId())
                || !assignment.getSourceNodeId().equals(replica.getNodeId())
                || !replicaAvailability.evaluate(replica).isUsable()) {
            throw RegistrationException.conflict("source dataset or replica is no longer available");
        }
        NodeManagement source = nodeMapper.getNodeById(assignment.getSourceNodeId());
        NodeManagement target = nodeMapper.getNodeById(assignment.getTargetNodeId());
        if (source == null || !isStorageNode(target) || !nodeAvailability.isSchedulable(target)
                || source.getNodeId().equals(target.getNodeId())) {
            throw RegistrationException.conflict("target storage node is no longer available");
        }
        topology.requirePath(source.getNodeId(), target.getNodeId());
        DatasetReplica targetReplica = datasetMapper.findReplicaByNodePath(target.getNodeId(), replica.getFilePath());
        if (targetReplica != null && !dataset.getDatasetId().equals(targetReplica.getDatasetId())) {
            throw RegistrationException.conflict("target path belongs to another dataset");
        }

        transfer.copyFrom(source, target, replica.getFilePath(), replica.getSizeBytes());
        transfer.scan(target);
        if (targetReplica == null) {
            datasetMapper.insertReplica(DatasetReplica.builder()
                    .datasetId(dataset.getDatasetId()).nodeId(target.getNodeId())
                    .filePath(replica.getFilePath()).sizeBytes(replica.getSizeBytes())
                    .checksum(replica.getChecksum()).availability("AVAILABLE")
                    .lastSeenAt(LocalDateTime.now(ZoneOffset.UTC))
                    .verifiedAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        } else {
            datasetMapper.updateReplicaAvailability(targetReplica.getReplicaId(), "AVAILABLE", true);
        }
        if ("MOVE".equals(assignment.getAction())) {
            // Do not remove the source or mark it missing until the target is persisted.
            // Deletion errors must fail the plan rather than silently report a successful move.
            transfer.delete(source, replica.getFilePath());
            datasetMapper.updateReplicaAvailability(replica.getReplicaId(), "MISSING", false);
        }
    }

    static boolean isStorageNode(NodeManagement node) {
        return node != null && ("storage".equalsIgnoreCase(node.getType())
                || "compute-storage".equalsIgnoreCase(node.getType()));
    }
}
