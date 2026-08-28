package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.RegisterDatasetRequest;
import org.example.dto.registration.RegisteredDatasetView;
import org.example.dto.registration.ResourceRequirements;
import org.example.dto.registration.UpdateDatasetRequest;
import org.example.entity.DatasetDiscoveryCandidate;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.RuntimeImage;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DatasetRegistrationService {
    private static final Pattern DATASET_CODE = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final TypeReference<Map<String, String>> STRING_MAP =
            new TypeReference<Map<String, String>>() { };

    private final DatasetRegistrationMapper mapper;
    private final NodeManagementMapper nodeMapper;
    private final RuntimeImageMapper runtimeImageMapper;
    private final RegistrationAuditMapper auditMapper;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final int discoveryPort;

    public DatasetRegistrationService(DatasetRegistrationMapper mapper,
                                      NodeManagementMapper nodeMapper,
                                      RuntimeImageMapper runtimeImageMapper,
                                      RegistrationAuditMapper auditMapper,
                                      ObjectMapper objectMapper,
                                      RestTemplate restTemplate,
                                      @Value("${dispatch.data-discovery.port:8080}") int discoveryPort) {
        this.mapper = mapper;
        this.nodeMapper = nodeMapper;
        this.runtimeImageMapper = runtimeImageMapper;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.discoveryPort = discoveryPort;
    }

    public String discover(Set<Integer> nodeIds) {
        List<NodeManagement> nodes = nodeMapper.listRegisteredNodes(null, "ACTIVE", true);
        if (nodeIds != null && !nodeIds.isEmpty()) {
            nodes = nodes.stream().filter(node -> nodeIds.contains(node.getNodeId())).collect(Collectors.toList());
        }
        int triggered = 0;
        for (NodeManagement node : nodes) {
            if (node.getInternalIp() == null || node.getInternalIp().isEmpty()) continue;
            String url = String.format("http://%s:%d/data-discovery/scan", node.getInternalIp(), discoveryPort);
            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                        && !"error".equals(String.valueOf(response.getBody().get("status")))) {
                    triggered++;
                }
            } catch (RestClientException ignored) {
                // Discovery is best effort: one offline node must not prevent other agents from scanning.
            }
        }
        String operationId = UUID.randomUUID().toString();
        audit("DATASET", null, "DISCOVER", operationId, "triggered=" + triggered);
        return operationId;
    }

    public List<DatasetDiscoveryCandidate> listCandidates(String query, Integer nodeId) {
        return mapper.listCandidates(query, nodeId, true);
    }

    public List<RegisteredDatasetView> listDatasets(String query, String status) {
        return mapper.listDatasets(query, status).stream().map(this::toView).collect(Collectors.toList());
    }

    public RegisteredDatasetView getDataset(Long datasetId) {
        return toView(requireDataset(datasetId));
    }

    @Transactional(noRollbackFor = RegistrationException.class)
    public RegisteredDatasetView register(RegisterDatasetRequest request, String requestId) {
        String existingResourceId = auditMapper.findResourceIdByRequest("DATASET", "REGISTER", requestId);
        if (existingResourceId != null) return getDataset(Long.valueOf(existingResourceId));
        validateRegisterRequest(request);
        DatasetDiscoveryCandidate candidate = mapper.findCandidateById(request.getCandidateId());
        if (candidate == null) throw RegistrationException.notFound("dataset candidate not found");
        if (candidate.getRegisteredDatasetId() != null) {
            throw RegistrationException.conflict("dataset candidate is already registered");
        }
        if (!"AVAILABLE".equals(candidate.getAvailability())) {
            throw RegistrationException.invalid("dataset candidate is not available");
        }
        NodeManagement node = nodeMapper.getNodeById(candidate.getNodeId());
        if (node == null) throw RegistrationException.invalid("candidate node is not registered");
        if (mapper.findDatasetByCodeAndVersion(request.getDatasetCode(), request.getVersion()) != null) {
            throw RegistrationException.conflict("dataset code and version already exist");
        }

        ResourceRequirements resources = request.getRequiredResources();
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetCode(request.getDatasetCode())
                .name(request.getName())
                .datasetVersion(request.getVersion())
                .description(request.getDescription())
                .dataType(request.getDataType())
                .labelsJson(writeJson(request.getLabels()))
                .requiredCpu(resources == null ? null : resources.getCpu())
                .requiredMemoryGi(resources == null ? null : resources.getMemoryGi())
                .requiredGpu(resources == null ? null : resources.getGpu())
                .status("DRAFT")
                .rowVersion(0)
                .build();
        mapper.insertDataset(dataset);

        DatasetReplica replica = DatasetReplica.builder()
                .datasetId(dataset.getDatasetId())
                .nodeId(candidate.getNodeId())
                .filePath(candidate.getFilePath())
                .sizeBytes(candidate.getSizeBytes())
                .checksum(candidate.getChecksum())
                .availability("AVAILABLE")
                .lastSeenAt(candidate.getLastSeenAt())
                .build();
        mapper.insertReplica(replica);
        mapper.markCandidateRegistered(candidate.getCandidateId(), dataset.getDatasetId());
        audit("DATASET", String.valueOf(dataset.getDatasetId()), "REGISTER", requestId, writeJson(request));
        return getDataset(dataset.getDatasetId());
    }

    @Transactional
    public RegisteredDatasetView update(Long datasetId, UpdateDatasetRequest request, String requestId) {
        if (request == null || request.getRowVersion() == null) {
            throw RegistrationException.invalid("rowVersion is required");
        }
        RegisteredDataset dataset = requireDataset(datasetId);
        dataset.setName(request.getName() == null ? dataset.getName() : request.getName());
        dataset.setDescription(request.getDescription() == null ? dataset.getDescription() : request.getDescription());
        dataset.setDataType(request.getDataType() == null ? dataset.getDataType() : request.getDataType());
        dataset.setLabelsJson(request.getLabels() == null ? dataset.getLabelsJson() : writeJson(request.getLabels()));
        ResourceRequirements resources = request.getRequiredResources();
        if (resources != null) {
            dataset.setRequiredCpu(resources.getCpu());
            dataset.setRequiredMemoryGi(resources.getMemoryGi());
            dataset.setRequiredGpu(resources.getGpu());
        }
        dataset.setRowVersion(request.getRowVersion());
        if (mapper.updateDataset(dataset) != 1) {
            throw RegistrationException.conflict("dataset was modified by another request");
        }
        audit("DATASET", String.valueOf(datasetId), "UPDATE", requestId, writeJson(request));
        return getDataset(datasetId);
    }

    @Transactional(noRollbackFor = RegistrationException.class)
    public RegisteredDatasetView verify(Long datasetId, String requestId) {
        requireDataset(datasetId);
        mapper.updateDatasetStatus(datasetId, "VERIFYING", null, false);
        List<DatasetReplica> replicas = mapper.listReplicas(datasetId);
        boolean available = false;
        for (DatasetReplica replica : replicas) {
            DatasetDiscoveryCandidate candidate = mapper.findCandidateByNodePath(
                    replica.getNodeId(), replica.getFilePath());
            boolean valid = candidate != null && "AVAILABLE".equals(candidate.getAvailability())
                    && (replica.getSizeBytes() == null || replica.getSizeBytes().equals(candidate.getSizeBytes()))
                    && (replica.getChecksum() == null || candidate.getChecksum() == null
                        || replica.getChecksum().equals(candidate.getChecksum()));
            mapper.updateReplicaAvailability(replica.getReplicaId(),
                    valid ? "AVAILABLE" : "VERIFY_FAILED", valid);
            available = available || valid;
        }
        if (!available) {
            mapper.updateDatasetStatus(datasetId, "VERIFY_FAILED", "no valid replica", false);
            audit("DATASET", String.valueOf(datasetId), "VERIFY_FAILED", requestId, "no valid replica");
            throw RegistrationException.invalid("dataset verification failed: no valid replica");
        }
        mapper.updateDatasetStatus(datasetId, "DRAFT", "verified", true);
        audit("DATASET", String.valueOf(datasetId), "VERIFY", requestId, "success");
        return getDataset(datasetId);
    }

    @Transactional
    public RegisteredDatasetView activate(Long datasetId, String requestId) {
        RegisteredDataset dataset = requireDataset(datasetId);
        if (dataset.getVerifiedAt() == null || mapper.countAvailableReplicas(datasetId) == 0) {
            throw RegistrationException.conflict("dataset must have a verified available replica");
        }
        mapper.updateDatasetStatus(datasetId, "ACTIVE", dataset.getVerificationMessage(), false);
        audit("DATASET", String.valueOf(datasetId), "ACTIVATE", requestId, null);
        return getDataset(datasetId);
    }

    @Transactional
    public RegisteredDatasetView disable(Long datasetId, String requestId) {
        requireDataset(datasetId);
        mapper.updateDatasetStatus(datasetId, "DISABLED", null, false);
        audit("DATASET", String.valueOf(datasetId), "DISABLE", requestId, null);
        return getDataset(datasetId);
    }

    @Transactional
    public DatasetReplica addReplica(Long datasetId, Long candidateId, String requestId) {
        requireDataset(datasetId);
        DatasetDiscoveryCandidate candidate = mapper.findCandidateById(candidateId);
        if (candidate == null) throw RegistrationException.notFound("dataset candidate not found");
        if (!"AVAILABLE".equals(candidate.getAvailability())) {
            throw RegistrationException.invalid("dataset candidate is not available");
        }
        DatasetReplica existing = mapper.findReplicaByDatasetNodePath(
                datasetId, candidate.getNodeId(), candidate.getFilePath());
        if (existing != null) return existing;
        DatasetReplica replica = DatasetReplica.builder()
                .datasetId(datasetId)
                .nodeId(candidate.getNodeId())
                .filePath(candidate.getFilePath())
                .sizeBytes(candidate.getSizeBytes())
                .checksum(candidate.getChecksum())
                .availability("AVAILABLE")
                .lastSeenAt(candidate.getLastSeenAt())
                .build();
        mapper.insertReplica(replica);
        mapper.markCandidateRegistered(candidateId, datasetId);
        audit("DATASET", String.valueOf(datasetId), "ADD_REPLICA", requestId, String.valueOf(replica.getReplicaId()));
        return mapper.findReplicaById(replica.getReplicaId());
    }

    public List<DatasetReplica> listReplicas(Long datasetId) {
        requireDataset(datasetId);
        return mapper.listReplicas(datasetId);
    }

    @Transactional
    public RegisteredDatasetView bindRuntimeImage(Long datasetId, Long runtimeImageId, String requestId) {
        requireDataset(datasetId);
        RuntimeImage image = runtimeImageMapper.findById(runtimeImageId);
        if (image == null) throw RegistrationException.notFound("runtime image not found");
        if (!"READY".equals(image.getStatus()) || !Boolean.TRUE.equals(image.getEnabled())) {
            throw RegistrationException.conflict("runtime image must be READY and enabled");
        }
        mapper.bindRuntimeImage(datasetId, runtimeImageId);
        audit("DATASET", String.valueOf(datasetId), "BIND_IMAGE", requestId, String.valueOf(runtimeImageId));
        return getDataset(datasetId);
    }

    @Transactional
    public void unregister(Long datasetId, String requestId) {
        RegisteredDataset dataset = requireDataset(datasetId);
        int taskReferences = mapper.countLegacyTaskReferences(dataset.getName());
        if (taskReferences > 0) {
            throw RegistrationException.conflict("dataset is referenced by " + taskReferences + " tasks");
        }
        mapper.softDeleteDataset(datasetId);
        audit("DATASET", String.valueOf(datasetId), "UNREGISTER", requestId, null);
    }

    private RegisteredDataset requireDataset(Long datasetId) {
        RegisteredDataset dataset = mapper.findDatasetById(datasetId);
        if (dataset == null) throw RegistrationException.notFound("registered dataset not found");
        return dataset;
    }

    private RegisteredDatasetView toView(RegisteredDataset dataset) {
        return RegisteredDatasetView.from(dataset, readLabels(dataset.getLabelsJson()),
                mapper.listReplicas(dataset.getDatasetId()));
    }

    private void validateRegisterRequest(RegisterDatasetRequest request) {
        if (request == null || request.getCandidateId() == null) {
            throw RegistrationException.invalid("candidateId is required");
        }
        if (request.getDatasetCode() == null || !DATASET_CODE.matcher(request.getDatasetCode()).matches()) {
            throw RegistrationException.invalid("datasetCode is invalid");
        }
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw RegistrationException.invalid("name is required");
        }
        if (request.getVersion() == null || request.getVersion().trim().isEmpty()) {
            throw RegistrationException.invalid("version is required");
        }
        if (request.getDataType() == null || request.getDataType().trim().isEmpty()) {
            throw RegistrationException.invalid("dataType is required");
        }
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("invalid JSON value", e); }
    }

    private Map<String, String> readLabels(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyMap();
        try { return objectMapper.readValue(json, STRING_MAP); }
        catch (JsonProcessingException e) { return Collections.emptyMap(); }
    }

    private void audit(String resourceType, String resourceId, String action,
                       String requestId, String detail) {
        auditMapper.insert(resourceType, resourceId, action, "system", requestId, detail);
    }
}
