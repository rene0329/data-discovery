package org.example.service;

import org.example.auth.AuthenticatedUser;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nothing else re-verifies a replica once it falls to UNVERIFIED/VERIFY_FAILED
 * (see DatasetReplicaAvailabilityService): the periodic discovery scan only
 * ever demotes, and DatasetRegistrationService.verify is otherwise only
 * reachable through the manual POST /datasets/{id}/verify endpoint. This
 * worker is the automatic path back to AVAILABLE, so a dataset whose replica
 * bounced (migration, a brief file absence, a re-registered node) recovers on
 * its own instead of staying stuck until someone happens to click "verify".
 */
@Service
public class ReplicaReverifyWorker {
    private static final Logger log = LoggerFactory.getLogger(ReplicaReverifyWorker.class);

    private final DatasetRegistrationMapper mapper;
    private final NodeManagementMapper nodeMapper;
    private final NodeAvailabilityService nodeAvailability;
    private final DatasetRegistrationService registrationService;

    @Value("${dataset.reverify.max-datasets-per-tick:5}")
    private int maxDatasetsPerTick;

    @Value("${dataset.reverify.retry-backoff-minutes:15}")
    private long retryBackoffMinutes;

    // Per-dataset backoff so a dataset whose replica genuinely fails
    // verification (corrupt file, wrong node) isn't re-attempted every tick.
    private final Map<Long, Instant> lastAttempt = new ConcurrentHashMap<>();

    public ReplicaReverifyWorker(DatasetRegistrationMapper mapper,
                                 NodeManagementMapper nodeMapper,
                                 NodeAvailabilityService nodeAvailability,
                                 DatasetRegistrationService registrationService) {
        this.mapper = mapper;
        this.nodeMapper = nodeMapper;
        this.nodeAvailability = nodeAvailability;
        this.registrationService = registrationService;
    }

    @Scheduled(fixedDelayString = "${dataset.reverify.interval-ms:300000}")
    public void reverifyStaleReplicas() {
        List<RegisteredDataset> activeDatasets = mapper.listDatasets(null, "ACTIVE");
        if (activeDatasets == null || activeDatasets.isEmpty()) return;

        int attempted = 0;
        for (RegisteredDataset dataset : activeDatasets) {
            if (attempted >= maxDatasetsPerTick) break;
            Long datasetId = dataset.getDatasetId();
            if (!needsReverify(datasetId)) continue;

            Instant last = lastAttempt.get(datasetId);
            if (last != null && Duration.between(last, Instant.now()).toMinutes() < retryBackoffMinutes) {
                continue;
            }
            lastAttempt.put(datasetId, Instant.now());
            attempted++;
            runAsSystem(() -> {
                try {
                    registrationService.verify(datasetId, "reverify-worker-" + UUID.randomUUID());
                    log.info("自动重新校验数据集 {} 完成", datasetId);
                } catch (RuntimeException error) {
                    // verify() already records VERIFY_FAILED / the per-replica reason;
                    // this loop must keep going for the remaining datasets.
                    log.warn("自动重新校验数据集 {} 失败: {}", datasetId, error.getMessage());
                }
            });
        }
    }

    /**
     * DatasetRegistrationService.verify() enforces owner/admin authorization
     * (requireDatasetMutation) through the request-scoped SecurityContext,
     * which does not exist on this scheduler thread. Run under a synthetic
     * ADMIN principal, the same "system" actor the audit log already uses for
     * automated writes, and always clear it afterward.
     */
    private void runAsSystem(Runnable action) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser system = new AuthenticatedUser(null, "system", "Replica Reverify Worker",
                Collections.singleton("ADMIN"), null, null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(system, null, Collections.emptyList()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private boolean needsReverify(Long datasetId) {
        List<DatasetReplica> replicas = mapper.listReplicas(datasetId);
        if (replicas == null) return false;
        for (DatasetReplica replica : replicas) {
            if (!"UNVERIFIED".equals(replica.getAvailability())
                    && !"VERIFY_FAILED".equals(replica.getAvailability())) {
                continue;
            }
            NodeManagement node = nodeMapper.getNodeById(replica.getNodeId());
            if (node != null && nodeAvailability.isSchedulable(node)) {
                return true;
            }
        }
        return false;
    }
}
