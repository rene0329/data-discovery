package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.DatasetReplica;
import org.example.entity.DatasetMetadata;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.SchedulingAssignment;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.example.model.FileIntegrityResult;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Data-only scheduling: no runtime images, compute tasks or Kubernetes Jobs. */
@Service
@Slf4j
public class DatasetSchedulingExecutor {
    private final DatasetRegistrationMapper datasetMapper;
    private final NodeManagementMapper nodeMapper;
    private final SchedulingPlanMapper planMapper;
    private final DatasetReplicaAvailabilityService replicaAvailability;
    private final NodeAvailabilityService nodeAvailability;
    private final DatasetUploadClient transfer;

    public DatasetSchedulingExecutor(DatasetRegistrationMapper datasetMapper,
                                     NodeManagementMapper nodeMapper,
                                     SchedulingPlanMapper planMapper,
                                     DatasetReplicaAvailabilityService replicaAvailability,
                                     NodeAvailabilityService nodeAvailability,
                                     DatasetUploadClient transfer) {
        this.datasetMapper = datasetMapper;
        this.nodeMapper = nodeMapper;
        this.planMapper = planMapper;
        this.replicaAvailability = replicaAvailability;
        this.nodeAvailability = nodeAvailability;
        this.transfer = transfer;
    }

    @Async
    @Transactional
    public void execute(Long planId, List<SchedulingAssignment> assignments) {
        long planStarted = System.nanoTime();
        planMapper.updatePlanStatus(planId, "RUNNING", null);
        log.info("【调度】存储调度开始 planId={} 操作数={} status=RUNNING",
                planId, assignments.size());
        int completed = 0;
        String failure = null;
        for (SchedulingAssignment assignment : assignments) {
            planMapper.updateAssignmentStatus(assignment.getAssignmentId(), "RUNNING", null);
            long assignmentStarted = System.nanoTime();
            log.info("【调度】操作开始 planId={} assignmentId={} datasetId={} replicaId={} "
                            + "sourceNodeId={} targetNodeId={} action={} status=RUNNING",
                    planId, assignment.getAssignmentId(), assignment.getDatasetId(), assignment.getReplicaId(),
                    assignment.getSourceNodeId(), assignment.getTargetNodeId(), assignment.getAction());
            try {
                transfer(assignment);
                planMapper.updateAssignmentStatus(assignment.getAssignmentId(), "COMPLETED", null);
                completed++;
                log.info("【调度】操作完成 planId={} assignmentId={} status=COMPLETED 总耗时={}秒",
                        planId, assignment.getAssignmentId(), StorageTransferProgress.secondsSince(assignmentStarted));
            } catch (Exception error) {
                failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                planMapper.updateAssignmentStatus(assignment.getAssignmentId(), "FAILED", failure);
                log.warn("【调度】操作失败 planId={} assignmentId={} status=FAILED 总耗时={}秒 error={}",
                        planId, assignment.getAssignmentId(), StorageTransferProgress.secondsSince(assignmentStarted), failure);
            }
        }
        String status = completed == assignments.size() ? "COMPLETED"
                : completed == 0 ? "FAILED" : "PARTIAL_COMPLETED";
        planMapper.updatePlanStatus(planId, status, failure);
        log.info("【调度】存储调度结束 planId={} 完成数={} 操作数={} "
                        + "status={} 总耗时={}秒",
                planId, completed, assignments.size(), status, StorageTransferProgress.secondsSince(planStarted));
    }

    private void transfer(SchedulingAssignment assignment) {
        if (!"COPY".equals(assignment.getAction()) && !"MOVE".equals(assignment.getAction())
                && !"DELETE".equals(assignment.getAction())) {
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
        if (source == null) {
            throw RegistrationException.conflict("source storage node is no longer available");
        }
        String accessRequestId = "storage-plan-" + assignment.getPlanId() + "-assignment-"
                + assignment.getAssignmentId();
        if ("DELETE".equals(assignment.getAction())) {
            datasetMapper.lockDataset(dataset.getDatasetId());
            datasetMapper.lockStorageNode(source.getNodeId());
            requireUnoccupied(dataset, assignment.getPlanId());
            boolean hasRemainingUsableReplica = datasetMapper.listReplicas(dataset.getDatasetId()).stream()
                    .filter(other -> !replica.getReplicaId().equals(other.getReplicaId()))
                    .anyMatch(other -> replicaAvailability.evaluate(other).isUsable());
            if (!hasRemainingUsableReplica) {
                throw RegistrationException.conflict("cannot delete the last available replica");
            }
            transfer.delete(source, replica.getFilePath(), dataset.getDatasetId(),
                    dataset.getDatasetVersion(), accessRequestId, null);
            datasetMapper.updateReplicaAvailability(replica.getReplicaId(), "MISSING", false);
            log.info("【数据迁移】源副本已删除 planId={} assignmentId={} datasetId={} sourceNode={}",
                    assignment.getPlanId(), assignment.getAssignmentId(), assignment.getDatasetId(), source.getNodeName());
            return;
        }

        NodeManagement target = nodeMapper.getNodeById(assignment.getTargetNodeId());
        if (source == null || !isStorageNode(target) || !nodeAvailability.isSchedulable(target)
                || source.getNodeId().equals(target.getNodeId())) {
            throw RegistrationException.conflict("target storage node is no longer available");
        }
        DatasetReplica targetReplica = datasetMapper.findReplicaByNodePath(target.getNodeId(), replica.getFilePath());
        if (targetReplica != null && !dataset.getDatasetId().equals(targetReplica.getDatasetId())) {
            throw RegistrationException.conflict("target path belongs to another dataset");
        }

        requireUnoccupied(dataset, assignment.getPlanId());
        DatasetMetadata metadata = datasetMapper.findDatasetMetadata(dataset.getDatasetId());
        String authority = requireAuthority(metadata);
        Long authoritativeSize = metadata.getAuthoritativeSizeBytes();

        FileIntegrityResult sourceIntegrity = transfer.verify(source, replica.getFilePath(),
                authoritativeSize, authority, dataset.getDatasetId(), dataset.getDatasetVersion(),
                accessRequestId, null);
        if (!matchesAuthority(sourceIntegrity, authoritativeSize, authority)) {
            if (sourceIntegrity == null) {
                datasetMapper.updateReplicaAvailability(replica.getReplicaId(), "VERIFY_FAILED", false);
            } else {
                datasetMapper.updateReplicaIntegrity(replica.getReplicaId(), sourceIntegrity.getSizeBytes(),
                        sourceIntegrity.getAlgorithm(), sourceIntegrity.getDigest(), "VERIFY_FAILED",
                        "source replica does not match dataset-version authority", false);
            }
            throw RegistrationException.conflict("source replica failed SHA-256 verification");
        }
        datasetMapper.updateReplicaIntegrity(replica.getReplicaId(), sourceIntegrity.getSizeBytes(),
                "SHA-256", sourceIntegrity.getDigest(), "AVAILABLE",
                "verified against dataset-version authority", true);
        // Keep the discovery candidate in step, or the next scan's upsert CASE
        // (DatasetRegistrationMapper.xml:upsertCandidate) will see a non-SHA-256
        // candidate row and demote this replica right back to UNVERIFIED.
        datasetMapper.updateCandidateIntegrity(source.getNodeId(), replica.getFilePath(),
                sourceIntegrity.getSizeBytes(), "SHA-256", sourceIntegrity.getDigest(), "AVAILABLE", true);
        log.info("【数据迁移】源副本校验通过 planId={} assignmentId={} datasetId={} sourceNode={}",
                assignment.getPlanId(), assignment.getAssignmentId(), assignment.getDatasetId(), source.getNodeName());

        boolean targetExisted = targetReplica != null;
        if (!targetExisted) {
            targetReplica = DatasetReplica.builder()
                    .datasetId(dataset.getDatasetId()).nodeId(target.getNodeId())
                    .filePath(replica.getFilePath()).sizeBytes(authoritativeSize)
                    .availability("VERIFYING")
                    .verificationMessage("copy in progress")
                    .lastSeenAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build();
            datasetMapper.insertReplica(targetReplica);
        }

        boolean targetValid = false;
        if (targetExisted && targetReplica.getReplicaId() != null
                && !"MISSING".equals(targetReplica.getAvailability())) {
            try {
                FileIntegrityResult existing = transfer.verify(target, replica.getFilePath(),
                        authoritativeSize, authority, dataset.getDatasetId(), dataset.getDatasetVersion(),
                        accessRequestId, null);
                targetValid = matchesAuthority(existing, authoritativeSize, authority);
                if (targetValid) {
                    datasetMapper.updateReplicaIntegrity(targetReplica.getReplicaId(), existing.getSizeBytes(),
                            "SHA-256", existing.getDigest(), "AVAILABLE",
                            "verified against dataset-version authority", true);
                    datasetMapper.updateCandidateIntegrity(target.getNodeId(), replica.getFilePath(),
                            existing.getSizeBytes(), "SHA-256", existing.getDigest(), "AVAILABLE", true);
                    log.info("【数据迁移】目标已有有效副本，复用并跳过传输 planId={} assignmentId={} datasetId={} targetNode={}",
                            assignment.getPlanId(), assignment.getAssignmentId(), assignment.getDatasetId(), target.getNodeName());
                }
            } catch (RuntimeException ignored) {
                targetValid = false;
            }
        }
        if (!targetValid) {
            datasetMapper.updateReplicaAvailability(targetReplica.getReplicaId(), "VERIFYING", false);
            log.info("【数据迁移】数据开始移动 planId={} assignmentId={} datasetId={} sourceNode={} targetNode={}",
                    assignment.getPlanId(), assignment.getAssignmentId(), assignment.getDatasetId(),
                    source.getNodeName(), target.getNodeName());
            long transferStarted = System.nanoTime();
            FileIntegrityResult copied;
            try (StorageTransferProgress progress = new StorageTransferProgress(assignment)) {
                copied = transfer.copyFrom(source, target, replica.getFilePath(),
                        authoritativeSize, authority, dataset.getDatasetId(), dataset.getDatasetVersion(), accessRequestId);
            }
            if (!matchesAuthority(copied, authoritativeSize, authority)) {
                if (copied == null) {
                    datasetMapper.updateReplicaAvailability(targetReplica.getReplicaId(), "VERIFY_FAILED", false);
                } else {
                    datasetMapper.updateReplicaIntegrity(targetReplica.getReplicaId(), copied.getSizeBytes(),
                            copied.getAlgorithm(), copied.getDigest(), "VERIFY_FAILED",
                            "copied replica does not match dataset-version authority", false);
                }
                throw RegistrationException.conflict("copied replica failed SHA-256 verification");
            }
            transfer.scan(target);
            datasetMapper.updateCandidateIntegrity(target.getNodeId(), replica.getFilePath(),
                    copied.getSizeBytes(), "SHA-256", copied.getDigest(), "AVAILABLE", true);
            datasetMapper.updateReplicaIntegrity(targetReplica.getReplicaId(), copied.getSizeBytes(),
                    "SHA-256", copied.getDigest(), "AVAILABLE",
                    "verified against dataset-version authority", true);
            log.info("【数据迁移】数据移动结束 [==========] planId={} assignmentId={} datasetId={} sourceNode={} targetNode={} 校验=通过 总耗时={}秒",
                    assignment.getPlanId(), assignment.getAssignmentId(), assignment.getDatasetId(),
                    source.getNodeName(), target.getNodeName(), StorageTransferProgress.secondsSince(transferStarted));
        }
        if ("MOVE".equals(assignment.getAction())) {
            // Do not remove the source or mark it missing until the target is persisted.
            // Deletion errors must fail the plan rather than silently report a successful move.
            datasetMapper.lockDataset(dataset.getDatasetId());
            datasetMapper.lockStorageNode(source.getNodeId());
            requireUnoccupied(dataset, assignment.getPlanId());
            transfer.delete(source, replica.getFilePath(), dataset.getDatasetId(),
                    dataset.getDatasetVersion(), accessRequestId, null);
            datasetMapper.updateReplicaAvailability(replica.getReplicaId(), "MISSING", false);
            log.info("【数据迁移】源副本已删除 planId={} assignmentId={} datasetId={} sourceNode={}",
                    assignment.getPlanId(), assignment.getAssignmentId(), assignment.getDatasetId(), source.getNodeName());
        }
    }

    private String requireAuthority(DatasetMetadata metadata) {
        if (metadata == null || metadata.getAuthoritativeSizeBytes() == null
                || !"SHA-256".equalsIgnoreCase(metadata.getDigestAlgorithm())) {
            throw RegistrationException.conflict("dataset version has no authoritative SHA-256");
        }
        String value = normalizeSha256(metadata.getDigestValue());
        if (value == null) throw RegistrationException.conflict("dataset version has no authoritative SHA-256");
        return value;
    }

    private boolean matchesAuthority(FileIntegrityResult result, Long size, String digest) {
        return result != null && result.isVerified() && result.getSizeBytes() == size
                && "SHA-256".equalsIgnoreCase(result.getAlgorithm())
                && digest.equals(normalizeSha256(result.getDigest()));
    }

    private String normalizeSha256(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("sha256:")) normalized = normalized.substring("sha256:".length());
        return normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private void requireUnoccupied(RegisteredDataset dataset, Long planId) {
        if (datasetMapper.countOtherSchedulingReferences(dataset.getDatasetId(), planId) > 0
                || datasetMapper.countActiveTaskReferences(dataset.getDatasetId(), dataset.getName()) > 0
                || datasetMapper.countActiveMigrationReferences(dataset.getDatasetId(), dataset.getLegacyDataId()) > 0) {
            throw RegistrationException.conflict("数据集出现新的任务占用，保留源副本并停止搬迁");
        }
    }

    static boolean isStorageNode(NodeManagement node) {
        return node != null && ("storage".equalsIgnoreCase(node.getType())
                || "compute-storage".equalsIgnoreCase(node.getType()));
    }
}
