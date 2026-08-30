package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.dto.registration.NodeCandidateView;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisterNodeRequest;
import org.example.dto.registration.RegisteredNodeView;
import org.example.dto.registration.UpdateNodeRequest;
import org.example.entity.NodeDiscoveryCandidate;
import org.example.entity.NodeManagement;
import org.example.entity.EdgeManagement;
import org.example.exception.RegistrationException;
import org.example.factory.K8sJobFactory;
import org.example.mapper.K8sNodeMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.NodeRegistrationMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.EdgeManagementMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NodeRegistrationService {
    private static final TypeReference<Map<String, String>> STRING_MAP =
            new TypeReference<Map<String, String>>() { };

    private final K8sJobFactory k8sJobFactory;
    private final K8sNodeMapper k8sNodeMapper;
    private final NodeRegistrationMapper registrationMapper;
    private final NodeManagementMapper nodeMapper;
    private final RegistrationAuditMapper auditMapper;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final int discoveryPort;
    private final int offlineFailureThreshold;
    private final NodeAvailabilityService availabilityService;
    private final EdgeManagementMapper edgeMapper;

    public NodeRegistrationService(K8sJobFactory k8sJobFactory,
                                   K8sNodeMapper k8sNodeMapper,
                                   NodeRegistrationMapper registrationMapper,
                                   NodeManagementMapper nodeMapper,
                                   RegistrationAuditMapper auditMapper,
                                   ObjectMapper objectMapper,
                                   RestTemplate restTemplate,
                                   NodeAvailabilityService availabilityService,
                                   EdgeManagementMapper edgeMapper,
                                   @Value("${dispatch.data-discovery.port:8080}") int discoveryPort,
                                   @Value("${app.node-sync.offline-failure-threshold:3}") int offlineFailureThreshold) {
        this.k8sJobFactory = k8sJobFactory;
        this.k8sNodeMapper = k8sNodeMapper;
        this.registrationMapper = registrationMapper;
        this.nodeMapper = nodeMapper;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.discoveryPort = discoveryPort;
        this.availabilityService = availabilityService;
        this.edgeMapper = edgeMapper;
        this.offlineFailureThreshold = Math.max(1, offlineFailureThreshold);
    }

    public OperationResult discover(Set<String> requestedClusterIds) {
        int[] observed = {0};
        int[] requested = {0};
        List<String> failedClusters = new ArrayList<>();
        k8sJobFactory.getClusterClients().forEach((clusterId, client) -> {
            if (requestedClusterIds != null && !requestedClusterIds.isEmpty()
                    && !requestedClusterIds.contains(clusterId)) {
                return;
            }
            try {
                List<Node> nodes = client.nodes().list().getItems();
                requested[0] += nodes.size();
                for (Node node : nodes) {
                    observeNode(node, client, clusterId);
                    observed[0]++;
                }
            } catch (RuntimeException ex) {
                failedClusters.add(clusterId);
            }
        });
        String operationId = UUID.randomUUID().toString();
        OperationResult result = OperationResult.discovery(
                operationId, requested[0], observed[0], failedClusters);
        audit("NODE", null, "DISCOVER", operationId,
                "requestedNodes=" + requested[0] + ",observedNodes=" + observed[0]
                        + ",failedClusters=" + failedClusters);
        return result;
    }

    @Transactional
    public void observeNode(Node k8sNode, KubernetesClient client, String clusterId) {
        NodeManagement observed = k8sNodeMapper.toEntityWithMetrics(k8sNode, client);
        if (observed == null || k8sNode.getMetadata() == null) {
            return;
        }
        String nodeName = k8sNode.getMetadata().getName();
        String k8sUid = k8sNode.getMetadata().getUid();
        if (k8sUid == null || k8sUid.isEmpty()) {
            k8sUid = "synthetic:" + clusterId + ":" + nodeName;
        }
        LocalDateTime now = LocalDateTime.now();
        String labelsJson = writeJson(k8sNode.getMetadata().getLabels());
        String observedStatus = observedStatus(k8sNode);
        String observedReason = observedReason(k8sNode, observedStatus);

        NodeDiscoveryCandidate candidate = NodeDiscoveryCandidate.builder()
                .clusterId(clusterId)
                .k8sUid(k8sUid)
                .k8sNodeName(nodeName)
                .internalIp(observed.getInternalIp())
                .externalIp(observed.getExternalIp())
                .observedRole(observed.getType())
                .maxCpu(observed.getMaxCpu())
                .maxMemory(observed.getMaxMemory())
                .currentCpu(observed.getCurrentCpu())
                .currentMemory(observed.getCurrentMemory())
                .observedStatus(observedStatus)
                .labelsJson(labelsJson)
                .lastSeenAt(now)
                .build();
        registrationMapper.upsertCandidate(candidate);
        candidate = registrationMapper.findCandidateByClusterAndUid(clusterId, k8sUid);

        NodeManagement registered = nodeMapper.getByClusterAndK8sUid(clusterId, k8sUid);
        if (registered == null) {
            // 兼容升级前已在 node_management 中的节点：首次观测时补齐 K8s 身份。
            NodeManagement legacy = nodeMapper.getNodeByName(nodeName);
            if (legacy != null && legacy.getK8sUid() == null) {
                nodeMapper.attachK8sIdentity(legacy.getNodeId(), clusterId, k8sUid);
                registered = nodeMapper.getNodeById(legacy.getNodeId());
            }
        }
        if (registered != null) {
            String registeredObservedStatus = observedStatus;
            String registeredObservedReason = observedReason;
            if ("ONLINE".equals(registeredObservedStatus) && requiresDiscoveryAgent(registered.getType())
                    && !isDiscoveryAgentHealthy(observed.getInternalIp())) {
                registeredObservedStatus = "AGENT_UNHEALTHY";
                registeredObservedReason = "data-discovery Agent health check failed";
            }
            String previousObservedStatus = registered.getObservedStatus();
            observed.setNodeId(registered.getNodeId());
            observed.setCluster(clusterId);
            observed.setK8sUid(k8sUid);
            observed.setLastSeenAt(now);
            observed.setObservedStatus(registeredObservedStatus);
            observed.setObservedStatusReason(registeredObservedReason);
            nodeMapper.updateNodeObservation(observed);
            if (candidate != null) {
                registrationMapper.markCandidateRegistered(candidate.getCandidateId(), registered.getNodeId());
            }
            if (!same(previousObservedStatus, registeredObservedStatus)) {
                audit("NODE", String.valueOf(registered.getNodeId()), "OBSERVED_STATUS_CHANGED",
                        "node-observation-" + clusterId + "-" + k8sUid,
                        safe(previousObservedStatus) + " -> " + registeredObservedStatus
                                + ": " + safe(registeredObservedReason));
            }
        }
    }

    @Transactional
    public void markOffline(Node node, String clusterId) {
        if (node == null || node.getMetadata() == null) return;
        String uid = node.getMetadata().getUid();
        if (uid == null || uid.isEmpty()) {
            uid = "synthetic:" + clusterId + ":" + node.getMetadata().getName();
        }
        registrationMapper.markCandidateOffline(clusterId, uid);
        recordOffline(clusterId, uid);
    }

    @Transactional
    public void reconcileMissingNodes(String clusterId, Set<String> observedUids) {
        for (NodeManagement node : nodeMapper.listRegisteredNodesByCluster(clusterId)) {
            if (node.getK8sUid() != null && !observedUids.contains(node.getK8sUid())) {
                recordOffline(clusterId, node.getK8sUid());
            }
        }
    }

    public List<NodeCandidateView> listCandidates(String query, String clusterId) {
        validateQuery(query);
        return registrationMapper.listCandidates(query, clusterId, true).stream()
                .map(candidate -> NodeCandidateView.from(candidate, readLabels(candidate.getLabelsJson())))
                .collect(Collectors.toList());
    }

    public List<RegisteredNodeView> listNodes(String query, String status, Boolean enabled) {
        validateQuery(query);
        validateNodeStatus(status);
        String normalizedStatus = status == null ? null : status.trim().toUpperCase();
        return nodeMapper.listRegisteredNodes(query, normalizedStatus, enabled).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    public RegisteredNodeView getNode(Integer nodeId) {
        return toView(requireNode(nodeId));
    }

    @Transactional(noRollbackFor = RegistrationException.class)
    public RegisteredNodeView register(RegisterNodeRequest request, String requestId) {
        String existingResourceId = auditMapper.findResourceIdByRequest("NODE", "REGISTER", requestId);
        if (existingResourceId != null) {
            return getNode(Integer.valueOf(existingResourceId));
        }
        if (request == null || request.getCandidateId() == null) {
            throw RegistrationException.invalid("candidateId is required");
        }
        if (request.getDisplayName() == null || request.getDisplayName().trim().isEmpty()) {
            throw RegistrationException.invalid("displayName is required");
        }
        NodeDiscoveryCandidate candidate = registrationMapper.findCandidateById(request.getCandidateId());
        if (candidate == null) throw RegistrationException.notFound("node candidate not found");
        if (candidate.getRegisteredNodeId() != null) {
            throw RegistrationException.conflict("node candidate is already registered");
        }
        if (!"ONLINE".equals(candidate.getObservedStatus())) {
            throw RegistrationException.invalid("node candidate is offline");
        }
        if (nodeMapper.getByClusterAndK8sUid(candidate.getClusterId(), candidate.getK8sUid()) != null) {
            throw RegistrationException.conflict("K8s node is already registered");
        }

        NodeManagement node = NodeManagement.builder()
                .nodeName(candidate.getK8sNodeName())
                .displayName(request.getDisplayName().trim())
                .externalIp(candidate.getExternalIp())
                .internalIp(candidate.getInternalIp())
                .type(toDatabaseRole(request.getRole()))
                .cluster(candidate.getClusterId())
                .k8sUid(candidate.getK8sUid())
                .maxCpu(candidate.getMaxCpu())
                .maxMemory(candidate.getMaxMemory())
                .currentCpu(candidate.getCurrentCpu())
                .currentMemory(candidate.getCurrentMemory())
                .numDataset(0)
                .lastUpdateTime(LocalDateTime.now())
                .registrationStatus("REGISTERED")
                .enabled(false)
                .observedStatus(candidate.getObservedStatus())
                .observedStatusReason(null)
                .offlineObservationCount(0)
                .labelsJson(writeJson(request.getLabels()))
                .lastSeenAt(candidate.getLastSeenAt())
                .rowVersion(0)
                .build();
        nodeMapper.insertRegisteredNode(node);
        registrationMapper.markCandidateRegistered(candidate.getCandidateId(), node.getNodeId());
        audit("NODE", String.valueOf(node.getNodeId()), "REGISTER", requestId, writeJson(request));

        if (Boolean.TRUE.equals(request.getEnabled())) {
            verify(node.getNodeId(), requestId);
            enable(node.getNodeId(), requestId);
        }
        return getNode(node.getNodeId());
    }

    @Transactional
    public RegisteredNodeView update(Integer nodeId, UpdateNodeRequest request, String requestId) {
        if (request == null || request.getVersion() == null) {
            throw RegistrationException.invalid("version is required");
        }
        NodeManagement existing = requireNode(nodeId);
        existing.setDisplayName(request.getDisplayName() == null
                ? existing.getDisplayName() : request.getDisplayName().trim());
        existing.setType(request.getRole() == null ? existing.getType() : toDatabaseRole(request.getRole()));
        existing.setLabelsJson(request.getLabels() == null
                ? existing.getLabelsJson() : writeJson(request.getLabels()));
        existing.setRowVersion(request.getVersion());
        if (nodeMapper.updateRegistrationMetadata(existing) != 1) {
            throw RegistrationException.conflict("node was modified by another request");
        }
        audit("NODE", String.valueOf(nodeId), "UPDATE", requestId, writeJson(request));
        return getNode(nodeId);
    }

    @Transactional(noRollbackFor = RegistrationException.class)
    public RegisteredNodeView verify(Integer nodeId, String requestId) {
        NodeManagement node = requireNode(nodeId);
        nodeMapper.updateRegistrationState(nodeId, "VERIFYING", false, false);
        try {
            KubernetesClient client = k8sJobFactory.getClusterClients().get(node.getCluster());
            if (client == null) throw new IllegalStateException("K8s cluster client is unavailable");
            Node k8sNode = client.nodes().withName(node.getNodeName()).get();
            if (k8sNode == null) throw new IllegalStateException("K8s node does not exist");
            if (requiresDiscoveryAgent(node.getType())) {
                verifyDiscoveryAgent(node.getInternalIp());
            }
            nodeMapper.updateRegistrationState(nodeId, "REGISTERED", false, true);
            audit("NODE", String.valueOf(nodeId), "VERIFY", requestId, "success");
            return getNode(nodeId);
        } catch (Exception ex) {
            nodeMapper.updateRegistrationState(nodeId, "VERIFY_FAILED", false, false);
            audit("NODE", String.valueOf(nodeId), "VERIFY_FAILED", requestId, ex.getMessage());
            throw RegistrationException.invalid("node verification failed: " + ex.getMessage());
        }
    }

    public RegisteredNodeView sync(Integer nodeId) {
        NodeManagement node = requireNode(nodeId);
        KubernetesClient client = k8sJobFactory.getClusterClients().get(node.getCluster());
        if (client == null) throw RegistrationException.invalid("K8s cluster client is unavailable");
        Node k8sNode = client.nodes().withName(node.getNodeName()).get();
        if (k8sNode == null) {
            recordOffline(node.getCluster(), node.getK8sUid());
            throw RegistrationException.invalid("K8s node does not exist");
        }
        observeNode(k8sNode, client, node.getCluster());
        return getNode(nodeId);
    }

    @Transactional
    public RegisteredNodeView enable(Integer nodeId, String requestId) {
        NodeManagement node = requireNode(nodeId);
        if (node.getVerifiedAt() == null || "VERIFY_FAILED".equals(node.getRegistrationStatus())
                || "OFFLINE".equals(node.getRegistrationStatus())) {
            throw RegistrationException.conflict("node must pass verification before it can be enabled");
        }
        nodeMapper.updateRegistrationState(nodeId, "ACTIVE", true, false);
        ensureUnknownLinks(nodeId);
        audit("NODE", String.valueOf(nodeId), "ENABLE", requestId, null);
        return getNode(nodeId);
    }

    /**
     * Make an enabled node visible in topology immediately. The probe DaemonSet
     * later replaces UNKNOWN with measured latency/bandwidth; UNKNOWN links are
     * deliberately not eligible for cross-node scheduling.
     */
    private void ensureUnknownLinks(Integer nodeId) {
        for (NodeManagement peer : nodeMapper.selectAllNodes()) {
            if (peer.getNodeId() == null || peer.getNodeId().equals(nodeId)
                    || !Boolean.TRUE.equals(peer.getEnabled())) {
                continue;
            }
            if (edgeMapper.findBySourceAndTargetNode(nodeId, peer.getNodeId()) == null) {
                edgeMapper.insertEdge(EdgeManagement.builder()
                        .sourceId(Math.min(nodeId, peer.getNodeId()))
                        .targetId(Math.max(nodeId, peer.getNodeId()))
                        .status("UNKNOWN")
                        .build());
            }
        }
    }

    @Transactional
    public RegisteredNodeView disable(Integer nodeId, String requestId) {
        requireNode(nodeId);
        nodeMapper.updateRegistrationState(nodeId, "DISABLED", false, false);
        audit("NODE", String.valueOf(nodeId), "DISABLE", requestId, null);
        return getNode(nodeId);
    }

    @Transactional
    public void unregister(Integer nodeId, String requestId) {
        requireNode(nodeId);
        int replicas = nodeMapper.countDatasetReplicasByNode(nodeId);
        if (replicas > 0) {
            throw RegistrationException.conflict("node still has " + replicas + " registered dataset replicas");
        }
        nodeMapper.softDeleteRegisteredNode(nodeId);
        audit("NODE", String.valueOf(nodeId), "UNREGISTER", requestId, null);
    }

    private NodeManagement requireNode(Integer nodeId) {
        NodeManagement node = nodeMapper.getNodeById(nodeId);
        if (node == null) throw RegistrationException.notFound("registered node not found");
        return node;
    }

    private RegisteredNodeView toView(NodeManagement node) {
        return RegisteredNodeView.from(node, readLabels(node.getLabelsJson()),
                availabilityService.evaluate(node));
    }

    @SuppressWarnings("unchecked")
    private void verifyDiscoveryAgent(String internalIp) {
        if (internalIp == null || internalIp.isEmpty()) {
            throw new IllegalStateException("node internal IP is missing");
        }
        String url = String.format("http://%s:%d/data-discovery/health", internalIp, discoveryPort);
        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
        Map<String, Object> body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || body == null
                || !"UP".equals(String.valueOf(body.get("status")))
                || !Boolean.TRUE.equals(body.get("dataDirectoryReadable"))) {
            throw new IllegalStateException("data-discovery Agent is not healthy");
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isDiscoveryAgentHealthy(String internalIp) {
        if (internalIp == null || internalIp.trim().isEmpty()) return false;
        try {
            String url = String.format("http://%s:%d/data-discovery/health", internalIp, discoveryPort);
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody();
            return response.getStatusCode().is2xxSuccessful() && body != null
                    && "UP".equals(String.valueOf(body.get("status")))
                    && Boolean.TRUE.equals(body.get("dataDirectoryReadable"));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String observedStatus(Node node) {
        if (node.getSpec() != null && Boolean.TRUE.equals(node.getSpec().getUnschedulable())) {
            return "UNSCHEDULABLE";
        }
        if (node.getStatus() == null || node.getStatus().getConditions() == null) return "NOT_READY";
        for (NodeCondition condition : node.getStatus().getConditions()) {
            if ("Ready".equals(condition.getType())) {
                return "True".equalsIgnoreCase(condition.getStatus()) ? "ONLINE" : "NOT_READY";
            }
        }
        return "NOT_READY";
    }

    private String observedReason(Node node, String status) {
        if ("UNSCHEDULABLE".equals(status)) return "Kubernetes node is cordoned";
        if (node.getStatus() != null && node.getStatus().getConditions() != null) {
            for (NodeCondition condition : node.getStatus().getConditions()) {
                if ("Ready".equals(condition.getType()) && !"True".equalsIgnoreCase(condition.getStatus())) {
                    String reason = condition.getReason();
                    String message = condition.getMessage();
                    return safe(reason) + (message == null || message.trim().isEmpty() ? "" : ": " + message);
                }
            }
        }
        return null;
    }

    private void recordOffline(String clusterId, String uid) {
        if (uid == null || uid.trim().isEmpty()) return;
        NodeManagement before = nodeMapper.getByClusterAndK8sUid(clusterId, uid);
        if (before == null) return;
        nodeMapper.markOfflineByClusterAndK8sUid(clusterId, uid, offlineFailureThreshold);
        NodeManagement after = nodeMapper.getByClusterAndK8sUid(clusterId, uid);
        if (after != null && !"OFFLINE".equals(before.getObservedStatus())
                && "OFFLINE".equals(after.getObservedStatus())) {
            audit("NODE", String.valueOf(after.getNodeId()), "OBSERVED_STATUS_CHANGED",
                    "node-offline-" + clusterId + "-" + uid,
                    safe(before.getObservedStatus()) + " -> OFFLINE after "
                            + after.getOfflineObservationCount() + " missed observations");
        }
    }

    private boolean same(String left, String right) {
        return safe(left).equals(safe(right));
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "UNKNOWN" : value.trim();
    }

    static boolean requiresDiscoveryAgent(String nodeRole) {
        return nodeRole == null || !"compute".equalsIgnoreCase(nodeRole.trim());
    }

    private String toDatabaseRole(String role) {
        if (role == null) throw RegistrationException.invalid("role is required");
        String normalized = role.trim().toUpperCase();
        switch (normalized) {
            case "COMPUTE": return "compute";
            case "STORAGE": return "storage";
            case "COMPUTE_STORAGE": return "compute-storage";
            case "WORKER": return "worker";
            default: throw RegistrationException.invalid("unsupported node role: " + role);
        }
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON value", e);
        }
    }

    private Map<String, String> readLabels(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }

    private void validateQuery(String query) {
        if (query != null && query.length() > 200) {
            throw RegistrationException.invalid("query must not exceed 200 characters");
        }
    }

    private void validateNodeStatus(String status) {
        if (status == null || status.trim().isEmpty()) return;
        String normalized = status.trim().toUpperCase();
        if (!java.util.Arrays.asList("REGISTERED", "VERIFYING", "VERIFY_FAILED", "ACTIVE",
                "DISABLED", "OFFLINE").contains(normalized)) {
            throw RegistrationException.invalid("unsupported node status: " + status);
        }
    }

    private void audit(String resourceType, String resourceId, String action,
                       String requestId, String detail) {
        auditMapper.insert(resourceType, resourceId, action, "system", requestId, detail);
    }
}
