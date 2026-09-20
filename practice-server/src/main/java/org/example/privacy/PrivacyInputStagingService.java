package org.example.privacy;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.StagingInput;
import org.example.security.access.AccessAuditContext;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.AccessScope;
import org.example.security.access.DatasetAccessAuthorizationService;
import org.example.service.DatasetReplicaAvailabilityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Selects a strongly verified replica and mints an ephemeral scoped READ token at dispatch time. */
@Service
public class PrivacyInputStagingService {
    private final DatasetRegistrationMapper datasets;
    private final NodeManagementMapper nodes;
    private final DatasetReplicaAvailabilityService availability;
    private final DatasetAccessAuthorizationService access;
    private final int agentPort;
    private final Map<String, String> partyNodeNames;

    public PrivacyInputStagingService(DatasetRegistrationMapper datasets,
                                      NodeManagementMapper nodes,
                                      DatasetReplicaAvailabilityService availability,
                                      DatasetAccessAuthorizationService access,
                                      @Value("${dispatch.data-discovery.port:8080}") int agentPort,
                                      @Value("${privacy-computing.parties.a.node-name:}") String partyANodeName,
                                      @Value("${privacy-computing.parties.b.node-name:}") String partyBNodeName,
                                      @Value("${privacy-computing.parties.c.node-name:}") String partyCNodeName) {
        this.datasets = datasets;
        this.nodes = nodes;
        this.availability = availability;
        this.access = access;
        this.agentPort = agentPort;
        this.partyNodeNames = new LinkedHashMap<>();
        this.partyNodeNames.put("A", normalizeNodeName(partyANodeName));
        this.partyNodeNames.put("B", normalizeNodeName(partyBNodeName));
        this.partyNodeNames.put("C", normalizeNodeName(partyCNodeName));
    }

    public List<StagingInput> prepare(String jobId, String attemptId, JobSpec spec) {
        List<StagingInput> result = new ArrayList<>();
        for (ParticipantSpec participant : spec.getParticipants()) {
            long datasetId = Long.parseLong(participant.getDatasetId());
            DatasetReplica replica = select(datasetId, participant);
            NodeManagement node = nodes.getNodeById(replica.getNodeId());
            if (node == null || blank(node.getNodeName())) {
                throw new IllegalStateException("selected privacy input node is unavailable");
            }
            String address = !blank(node.getInternalIp()) ? node.getInternalIp() : node.getExternalIp();
            if (blank(address)) throw new IllegalStateException("selected privacy input node has no address");
            AccessScope scope = new AccessScope(participant.getDatasetId(), participant.getDatasetVersion(),
                    replica.getFilePath(), "READ", node.getNodeName());
            AccessAuthorizationResult token = access.issueInternalOneTime(scope,
                    new AccessAuditContext(jobId + ":" + participant.getPartyId(), attemptId, null));
            if (participant.getFrozenSchema() == null || participant.getFrozenSchema().isEmpty()) {
                throw new IllegalStateException("frozen dataset schema is unavailable at dispatch");
            }

            StagingInput input = new StagingInput();
            input.setPartyId(participant.getPartyId());
            input.setDatasetId(participant.getDatasetId());
            input.setDatasetVersion(participant.getDatasetVersion());
            input.setExpectedSha256(digestWire(participant.getDatasetSha256()));
            input.setExpectedSize(participant.getAuthoritativeSizeBytes());
            input.setExpectedSchema(participant.getFrozenSchema());
            input.setExpectedSchemaDigest(digestWire(participant.getSchemaDigest()));
            input.setNodeId(node.getNodeId());
            input.setNodeName(node.getNodeName());
            input.setAgentBaseUrl("http://" + host(address) + ":" + agentPort);
            input.setSourcePath(replica.getFilePath());
            input.setTokenType(token.getTokenType());
            input.setToken(token.getToken());
            input.setExpiresAt(token.getExpiresAt());
            result.add(input);
        }
        return result;
    }

    /**
     * Verifies that every runtime slot can stage the frozen dataset version from
     * a strongly verified replica. Business domains are mapped to A/B/C per job,
     * so a slot is not permanently coupled to one storage node. The former fixed
     * node remains a locality preference when that replica is available.
     */
    public void validateAvailableReplicas(JobSpec spec) {
        if (spec == null || spec.getParticipants() == null) {
            throw RegistrationException.invalid("PRIVACY_PARTICIPANTS_REQUIRED",
                    "privacy job participants are required");
        }
        for (ParticipantSpec participant : spec.getParticipants()) {
            long datasetId;
            try {
                datasetId = Long.parseLong(participant.getDatasetId());
            } catch (RuntimeException ex) {
                throw RegistrationException.invalid("PRIVACY_DATASET_ID_INVALID",
                        "datasetId must be numeric for party " + participant.getPartyId());
            }
            select(datasetId, participant);
        }
    }

    /** Compatibility alias for callers compiled against the fixed-domain API. */
    @Deprecated
    public void validateFixedNodeReplicas(JobSpec spec) {
        validateAvailableReplicas(spec);
    }

    private DatasetReplica select(long datasetId, ParticipantSpec participant) {
        String party = participant.getPartyId() == null
                ? "" : participant.getPartyId().trim().toUpperCase(Locale.ROOT);
        String requiredNodeName = partyNodeNames.get(party);
        List<DatasetReplica> replicas = datasets.listReplicas(datasetId);
        if (replicas == null) replicas = new ArrayList<>();
        return replicas.stream()
                .filter(item -> availability.evaluate(item).isUsable())
                .filter(item -> participant.getDatasetSha256().equals(normalize(item.getChecksum())))
                .filter(item -> participant.getAuthoritativeSizeBytes().equals(item.getSizeBytes()))
                .sorted(Comparator.comparing((DatasetReplica item) ->
                                replicaBelongsTo(item, requiredNodeName) ? 0 : 1)
                        .thenComparing(DatasetReplica::getNodeId)
                        .thenComparing(DatasetReplica::getFilePath))
                .findFirst()
                .orElseThrow(() -> RegistrationException.conflict(
                        "PRIVACY_INPUT_REPLICA_UNAVAILABLE",
                        "runtime slot " + party + " has no usable replica for the frozen dataset version"));
    }

    private boolean replicaBelongsTo(DatasetReplica replica, String requiredNodeName) {
        if (blank(requiredNodeName) || replica == null || replica.getNodeId() == null) return false;
        NodeManagement node = nodes.getNodeById(replica.getNodeId());
        return node != null && requiredNodeName.equals(normalizeNodeName(node.getNodeName()));
    }

    private String normalizeNodeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        if (value == null) return "";
        String result = value.trim().toLowerCase();
        return result.startsWith("sha256:") ? result.substring(7) : result;
    }

    private String host(String value) {
        String trimmed = value.trim();
        return trimmed.contains(":") && !trimmed.startsWith("[") ? "[" + trimmed + "]" : trimmed;
    }

    private String digestWire(String value) {
        if (blank(value)) throw new IllegalStateException("frozen SHA-256 digest is unavailable");
        String normalized = value.trim().toLowerCase();
        return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
    }

    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
