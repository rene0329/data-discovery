package org.example.access;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetAccessEventMapper;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.service.DataTransferAddressResolver;
import org.example.service.DatasetHeatService;
import org.example.service.DatasetReplicaAvailabilityService;
import org.example.service.NetworkTopologyService;
import org.example.security.access.AccessAuditContext;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.AccessScope;
import org.example.security.access.DatasetAccessAuthorizationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DatasetAccessService {
    private final DatasetRegistrationMapper datasets;
    private final NodeManagementMapper nodes;
    private final DatasetReplicaAvailabilityService availability;
    private final NetworkTopologyService topology;
    private final DatasetAccessEventMapper events;
    private final DatasetHeatService heat;
    private final NodeDatasetReadClient reads;
    private final DatasetAccessAuthorizationService authorization;
    private final DataTransferAddressResolver transferAddresses;
    private final int discoveryPort;

    public DatasetAccessService(DatasetRegistrationMapper datasets, NodeManagementMapper nodes,
            DatasetReplicaAvailabilityService availability, NetworkTopologyService topology,
            DatasetAccessEventMapper events, DatasetHeatService heat, NodeDatasetReadClient reads,
            DatasetAccessAuthorizationService authorization, DataTransferAddressResolver transferAddresses,
            @Value("${dispatch.data-discovery.port:8080}") int discoveryPort) {
        this.datasets = datasets; this.nodes = nodes; this.availability = availability;
        this.topology = topology; this.events = events; this.heat = heat; this.reads = reads;
        this.authorization = authorization;
        this.transferAddresses = transferAddresses;
        this.discoveryPort = discoveryPort;
    }

    public DatasetAccessEvent read(Long datasetId, DatasetAccessRequest request,
                                   String authorizationHeader, String clientIp) {
        if (request == null || request.getConsumerNodeId() == null) {
            throw RegistrationException.invalid("consumerNodeId is required");
        }
        String requestId = blank(request.getRequestId()) ? UUID.randomUUID().toString() : request.getRequestId().trim();
        DatasetAccessEvent existing = events.findByRequestId(requestId);
        if (existing != null) return existing;
        RegisteredDataset dataset = datasets.findDatasetById(datasetId);
        if (dataset == null || !"ACTIVE".equals(dataset.getStatus())) {
            throw RegistrationException.conflict("dataset is not ACTIVE: " + datasetId);
        }
        NodeManagement consumer = nodes.getNodeById(request.getConsumerNodeId());
        if (consumer == null || !("compute".equalsIgnoreCase(consumer.getType())
                || "compute-storage".equalsIgnoreCase(consumer.getType()))) {
            throw RegistrationException.invalid("consumer must be a COMPUTE or COMPUTE_STORAGE node");
        }
        List<DatasetReplica> usable = datasets.listReplicas(datasetId).stream()
                .filter(replica -> availability.evaluate(replica).isUsable())
                .filter(replica -> replica.getSizeBytes() != null && replica.getSizeBytes() >= 0)
                .filter(replica -> replica.getChecksum() != null && replica.getChecksum().matches("(?i)[0-9a-f]{64}"))
                .collect(Collectors.toList());
        if (usable.isEmpty()) throw RegistrationException.conflict("dataset has no SHA-256 verified replica");
        java.util.Map<Integer, NetworkTopologyService.NetworkPath> paths = topology.pathsFrom(consumer.getNodeId());
        DatasetReplica source = usable.stream().filter(replica -> paths.containsKey(replica.getNodeId()))
                .min(Comparator.comparingInt((DatasetReplica replica) ->
                                replica.getNodeId().equals(consumer.getNodeId()) ? 0 : 1)
                        .thenComparingDouble(replica -> paths.get(replica.getNodeId()).getLatencyMs())
                        .thenComparing((DatasetReplica replica) ->
                                -paths.get(replica.getNodeId()).getBandwidthMbps())
                        .thenComparing(DatasetReplica::getReplicaId)).orElse(null);
        if (source == null) throw RegistrationException.conflict("no reachable verified replica from consumer node");
        NodeManagement sourceNode = nodes.getNodeById(source.getNodeId());
        AccessAuditContext auditContext = new AccessAuditContext(requestId, trim(request.getRunId()), clientIp);
        AccessAuthorizationResult sourceGrant = authorization.authorizeAndIssue(authorizationHeader,
                new AccessScope(String.valueOf(datasetId), dataset.getDatasetVersion(), source.getFilePath(),
                        "READ", sourceNode.getNodeName()), auditContext);
        AccessAuthorizationResult consumerGrant = source.getNodeId().equals(consumer.getNodeId())
                ? sourceGrant : authorization.issueInternal(
                new AccessScope(String.valueOf(datasetId), dataset.getDatasetVersion(), source.getFilePath(),
                        "READ", consumer.getNodeName()), auditContext);
        DatasetAccessEvent event = DatasetAccessEvent.builder().requestId(requestId)
                .runId(trim(request.getRunId())).taskId(request.getTaskId()).datasetId(datasetId)
                .datasetVersion(dataset.getDatasetVersion()).consumerNodeId(consumer.getNodeId())
                .sourceReplicaId(source.getReplicaId()).sourceNodeId(source.getNodeId())
                .routeReason(routeReason(consumer, sourceNode, paths.get(source.getNodeId())))
                .expectedBytes(source.getSizeBytes()).bytesRead(0L).cacheHitBytes(0L)
                .startedAt(LocalDateTime.now(ZoneOffset.UTC)).success(false).build();
        events.insertStarted(event);
        long startedNanos = System.nanoTime();
        try {
            NodeReadCommand command = command(event, dataset, source, sourceNode, consumer,
                    request.isClearCache(), sourceGrant.getToken());
            NodeReadOutcome outcome = reads.read(consumer, command, consumerGrant.getToken());
            if (outcome == null || outcome.getBytesRead() != source.getSizeBytes()
                    || !source.getChecksum().equalsIgnoreCase(outcome.getChecksum())) {
                throw RegistrationException.conflict("node returned incomplete or inconsistent read evidence");
            }
            LocalDateTime completedAt = LocalDateTime.now(ZoneOffset.UTC);
            event.setCacheLayer(outcome.getLayer());
            event.setBytesRead(outcome.getBytesRead());
            event.setCacheHitBytes(outcome.getCacheHitBytes());
            event.setChecksum(outcome.getChecksum());
            event.setFirstByteAt(event.getStartedAt().plusNanos(outcome.getFirstByteMs() * 1_000_000L));
            event.setCompletedAt(completedAt);
            event.setDurationMs(outcome.getDurationMs());
            event.setSuccess(true);
            events.complete(event);
            heat.recordAccess(datasetId);
            return events.findByRequestId(requestId);
        } catch (RuntimeException error) {
            event.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
            event.setDurationMs((System.nanoTime() - startedNanos) / 1_000_000L);
            event.setFailureReason(limit(error.getMessage(), 1024));
            events.fail(event);
            throw error;
        }
    }

    public List<DatasetAccessEvent> list(Long datasetId, String runId, int limit) {
        return events.list(datasetId, trim(runId), Math.max(1, Math.min(200, limit)));
    }

    private NodeReadCommand command(DatasetAccessEvent event, RegisteredDataset dataset,
            DatasetReplica source, NodeManagement sourceNode, NodeManagement consumer,
            boolean clearCache, String sourceToken) {
        NodeReadCommand command = new NodeReadCommand();
        command.setRequestId(event.getRequestId());
        command.setDatasetId(String.valueOf(dataset.getDatasetId()));
        command.setDatasetVersion(dataset.getDatasetVersion());
        command.setSourcePath(source.getFilePath());
        command.setCacheKey(dataset.getDatasetId() + ":" + dataset.getDatasetVersion() + ":" + source.getChecksum());
        if (event.getConsumerNodeId().equals(source.getNodeId())) command.setLocalPath(source.getFilePath());
        else command.setSourceUrl("http://" + transferAddresses.sourceAddress(sourceNode, consumer) + ":" + discoveryPort
                + "/data-discovery/download/" + encodePath(source.getFilePath()));
        command.setSourceToken(sourceToken);
        command.setExpectedSize(source.getSizeBytes());
        command.setExpectedChecksum(source.getChecksum());
        command.setClearCache(clearCache);
        return command;
    }

    private static String routeReason(NodeManagement consumer, NodeManagement source,
                                      NetworkTopologyService.NetworkPath path) {
        if (consumer.getNodeId().equals(source.getNodeId())) return "verified replica on consumer node";
        return "lowest-latency verified replica: " + path.getLatencyMs() + "ms, "
                + path.getBandwidthMbps() + "Mbps via " + path.getNodeIds();
    }
    private static String encodePath(String path) {
        StringBuilder encoded = new StringBuilder();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty()) continue;
            if (encoded.length() > 0) encoded.append('/');
            try { encoded.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20")); }
            catch (UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
        }
        return encoded.toString();
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String trim(String value) { return blank(value) ? null : value.trim(); }
    private static String limit(String value, int max) {
        if (value == null) return "unknown failure";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
