package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.example.dto.registration.RegisterDatasetRequest;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisteredDatasetView;
import org.example.dto.registration.ResourceRequirements;
import org.example.dto.registration.UpdateDatasetRequest;
import org.example.dto.registration.UploadDatasetRequest;
import org.example.entity.DatasetDiscoveryCandidate;
import org.example.entity.DatasetMetadata;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.RuntimeImage;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.example.model.FileIntegrityResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
    private final DatasetReplicaAvailabilityService replicaAvailabilityService;
    private final DatasetUploadClient uploadClient;
    private final NodeAvailabilityService nodeAvailabilityService;
    private final String dataDirectory;
    private final TransactionTemplate transactionTemplate;

    public DatasetRegistrationService(DatasetRegistrationMapper mapper,
                                      NodeManagementMapper nodeMapper,
                                      RuntimeImageMapper runtimeImageMapper,
                                      RegistrationAuditMapper auditMapper,
                                      ObjectMapper objectMapper,
                                      RestTemplate restTemplate,
                                      DatasetReplicaAvailabilityService replicaAvailabilityService,
                                      DatasetUploadClient uploadClient,
                                      NodeAvailabilityService nodeAvailabilityService,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${dispatch.data-discovery.port:8080}") int discoveryPort,
                                      @Value("${dispatch.data-discovery.data-directory:/dataset}") String dataDirectory) {
        this.mapper = mapper;
        this.nodeMapper = nodeMapper;
        this.runtimeImageMapper = runtimeImageMapper;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.discoveryPort = discoveryPort;
        this.replicaAvailabilityService = replicaAvailabilityService;
        this.uploadClient = uploadClient;
        this.nodeAvailabilityService = nodeAvailabilityService;
        this.dataDirectory = dataDirectory;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public OperationResult discover(Set<Integer> nodeIds) {
        List<NodeManagement> nodes = nodeMapper.listRegisteredNodes(null, null, null).stream()
                .filter(this::isEligibleDiscoveryNode)
                .collect(Collectors.toList());
        if (nodeIds != null && !nodeIds.isEmpty()) {
            nodes = nodes.stream().filter(node -> nodeIds.contains(node.getNodeId())).collect(Collectors.toList());
        }
        int triggered = 0;
        List<String> failedNodes = new java.util.ArrayList<>();
        for (NodeManagement node : nodes) {
            if (node.getInternalIp() == null || node.getInternalIp().isEmpty()) continue;
            String url = String.format("http://%s:%d/data-discovery/scan", node.getInternalIp(), discoveryPort);
            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                        && !"error".equals(String.valueOf(response.getBody().get("status")))) {
                    triggered++;
                } else {
                    failedNodes.add(node.getNodeName());
                }
            } catch (RestClientException ex) {
                failedNodes.add(node.getNodeName());
            }
        }
        String operationId = UUID.randomUUID().toString();
        OperationResult result = OperationResult.discovery(
                operationId, nodes.size(), triggered, failedNodes);
        audit("DATASET", null, "DISCOVER", operationId,
                "requestedNodes=" + nodes.size() + ",triggeredNodes=" + triggered
                        + ",failedNodes=" + failedNodes);
        return result;
    }

    public List<DatasetDiscoveryCandidate> listCandidates(String query, Integer nodeId) {
        validateQuery(query);
        return mapper.listCandidates(query, nodeId, true);
    }

    public List<RegisteredDatasetView> listDatasets(String query, String status) {
        validateQuery(query);
        validateDatasetStatus(status);
        String normalizedStatus = status == null ? null : status.trim().toUpperCase();
        return mapper.listDatasets(query, normalizedStatus).stream()
                .map(this::toView).collect(Collectors.toList());
    }

    public RegisteredDatasetView getDataset(Long datasetId) {
        return toView(requireDataset(datasetId));
    }

    @Transactional
    public RegisteredDatasetView register(RegisterDatasetRequest request, String requestId) {
        String existingResourceId = auditMapper.findResourceIdByRequest("DATASET", "REGISTER", requestId);
        if (existingResourceId != null) return getDataset(Long.valueOf(existingResourceId));
        if (request == null || request.getCandidateId() == null) {
            throw RegistrationException.invalid("candidateId is required");
        }
        DatasetDiscoveryCandidate candidate = mapper.findCandidateById(request.getCandidateId());
        if (candidate == null) throw RegistrationException.notFound("dataset candidate not found");
        String metadataJson = firstText(request.getMetadataJson(), candidate.getMetadataJson());
        applyMetadata(request, metadataJson);
        validateRegisterRequest(request);
        if (candidate.getRegisteredDatasetId() != null) {
            throw RegistrationException.conflict("dataset candidate is already registered");
        }
        if ("MISSING".equals(candidate.getAvailability())) {
            throw RegistrationException.invalid("dataset candidate is missing");
        }
        if (mapper.findReplicaByNodePath(candidate.getNodeId(), candidate.getFilePath()) != null) {
            throw RegistrationException.conflict("dataset file is already registered");
        }
        NodeManagement node = nodeMapper.getNodeById(candidate.getNodeId());
        if (node == null) throw RegistrationException.invalid("candidate node is not registered");
        if (mapper.findDatasetByCodeAndVersion(request.getDatasetCode(), request.getVersion()) != null) {
            throw RegistrationException.conflict("dataset code and version already exist");
        }

        FileIntegrityResult authority = verifyCandidate(node, candidate,
                metadataDigest(metadataJson), null, request.getVersion(), requestId);

        ResourceRequirements resources = request.getRequiredResources();
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetCode(request.getDatasetCode())
                .name(request.getName())
                .datasetVersion(request.getVersion())
                .description(request.getDescription())
                .dataType(request.getDataType())
                .category(firstText(request.getCategory(), "OTHER"))
                .dataFormat(firstText(request.getFormat(), request.getDataType(), "NPZ"))
                .labelsJson(writeJson(request.getLabels()))
                .requiredCpu(resources == null ? null : resources.getCpu())
                .requiredMemoryGi(resources == null ? null : resources.getMemoryGi())
                .requiredGpu(resources == null ? null : resources.getGpu())
                .status("DRAFT")
                .rowVersion(0)
                .build();
        mapper.insertDataset(dataset);
        persistDatasetMetadata(dataset.getDatasetId(), metadataJson, request, authority);

        DatasetReplica replica = DatasetReplica.builder()
                .datasetId(dataset.getDatasetId())
                .nodeId(candidate.getNodeId())
                .filePath(candidate.getFilePath())
                .sizeBytes(authority.getSizeBytes())
                .checksumAlgorithm("SHA-256")
                .checksum(authority.getDigest())
                .availability("AVAILABLE")
                .verificationMessage("verified against dataset-version authority")
                .lastSeenAt(candidate.getLastSeenAt())
                .verifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        mapper.insertReplica(replica);
        mapper.markCandidateRegistered(candidate.getCandidateId(), dataset.getDatasetId());
        audit("DATASET", String.valueOf(dataset.getDatasetId()), "REGISTER", requestId, writeJson(request));
        return getDataset(dataset.getDatasetId());
    }

    /**
     * Streams a new dataset file to an available storage node, synchronously
     * refreshes that node's discovery candidates, then reuses normal candidate
     * registration to create the dataset and its first replica.
     */
    public RegisteredDatasetView uploadAndRegister(UploadDatasetRequest request,
                                                   MultipartFile file,
                                                   String requestId) {
        validateUploadRequest(request, file);
        NodeManagement node = nodeMapper.getNodeById(request.getNodeId());
        if (node == null) throw RegistrationException.notFound("target node is not registered");
        if (!isStorageRole(node.getType())) {
            throw RegistrationException.invalid("UPLOAD_NODE_NOT_STORAGE",
                    "dataset files can only be uploaded to STORAGE or COMPUTE_STORAGE nodes");
        }
        NodeAvailability availability = nodeAvailabilityService.evaluate(node);
        if (!availability.isSchedulable()) {
            throw RegistrationException.conflict("UPLOAD_NODE_UNAVAILABLE",
                    "target node is unavailable: " + availability.getReason());
        }
        if (mapper.findDatasetByCodeAndVersion(request.getDatasetCode(), request.getVersion()) != null) {
            throw RegistrationException.conflict("dataset code and version already exist");
        }

        String relativePath = uploadRelativePath(request, file);
        Path root = Paths.get(dataDirectory).toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw RegistrationException.invalid("invalid dataset upload path");
        }
        String absolutePath = target.toString();
        if (mapper.findReplicaByNodePath(node.getNodeId(), absolutePath) != null) {
            throw RegistrationException.conflict("dataset file is already registered");
        }

        boolean uploaded = false;
        try {
            uploadClient.upload(node, file, relativePath, null, request.getVersion(), requestId, null);
            uploaded = true;
            uploadClient.scan(node);
            DatasetDiscoveryCandidate candidate = mapper.findCandidateByNodePath(
                    node.getNodeId(), absolutePath);
            if (candidate == null || "MISSING".equals(candidate.getAvailability())) {
                throw RegistrationException.invalid("DATASET_UPLOAD_NOT_DISCOVERED",
                        "uploaded file was not discovered on the target node");
            }
            if (candidate.getSizeBytes() != null && candidate.getSizeBytes() != file.getSize()) {
                throw RegistrationException.invalid("DATASET_UPLOAD_SIZE_MISMATCH",
                        "uploaded file size does not match the discovered file");
            }

            RegisterDatasetRequest register = new RegisterDatasetRequest();
            register.setCandidateId(candidate.getCandidateId());
            register.setDatasetCode(request.getDatasetCode());
            register.setName(request.getName());
            register.setVersion(request.getVersion());
            register.setDescription(request.getDescription());
            register.setDataType(request.getDataType());
            register.setCategory(request.getCategory());
            register.setFormat(request.getFormat());
            register.setMetadataJson(request.getMetadataJson());
            register.setLabels(request.getLabels());
            register.setRequiredResources(request.getRequiredResources());
            return transactionTemplate.execute(status -> register(register, requestId));
        } catch (RuntimeException ex) {
            if (uploaded) {
                uploadClient.deleteQuietly(node, absolutePath, null,
                        request.getVersion(), requestId, null);
                try {
                    uploadClient.scan(node);
                } catch (RuntimeException cleanupError) {
                    // The physical file cleanup is primary. A periodic scan will
                    // reconcile a stale candidate if this best-effort scan fails.
                }
            }
            throw ex;
        }
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
        RegisteredDataset dataset = requireDataset(datasetId);
        mapper.updateDatasetStatus(datasetId, "VERIFYING", null, false);
        DatasetMetadata metadata = mapper.findDatasetMetadata(datasetId);
        String authoritativeDigest = metadata == null ? null : normalizeSha256(metadata.getDigestValue());
        Long authoritativeSize = metadata == null ? null : metadata.getAuthoritativeSizeBytes();
        boolean invalidAuthority = metadata != null && !blank(metadata.getDigestValue())
                && (!"SHA-256".equalsIgnoreCase(metadata.getDigestAlgorithm())
                    || authoritativeDigest == null);
        List<DatasetReplica> replicas = mapper.listReplicas(datasetId);
        boolean available = false;
        for (DatasetReplica replica : replicas) {
            FileIntegrityResult measurement = null;
            boolean valid = false;
            String message;
            try {
                if (invalidAuthority) {
                    throw new IllegalStateException("dataset-version authority is not SHA-256");
                }
                NodeManagement node = nodeMapper.getNodeById(replica.getNodeId());
                if (node == null) throw new IllegalStateException("replica node is not registered");
                Long expectedSize = authoritativeSize == null ? replica.getSizeBytes() : authoritativeSize;
                measurement = uploadClient.verify(node, replica.getFilePath(),
                        expectedSize, authoritativeDigest, datasetId,
                        dataset.getDatasetVersion(), requestId, null);
                if (authoritativeDigest == null) {
                    if (!validMeasurement(measurement, expectedSize)) {
                        throw new IllegalStateException("replica returned no usable SHA-256 measurement");
                    }
                    authoritativeDigest = normalizeSha256(measurement.getDigest());
                    authoritativeSize = measurement.getSizeBytes();
                    if (metadata == null) {
                        metadata = DatasetMetadata.builder().datasetId(datasetId)
                                .metadataVersion("1.0")
                                .authoritativeSizeBytes(authoritativeSize)
                                .digestAlgorithm("SHA-256").digestValue(authoritativeDigest).build();
                        mapper.upsertDatasetMetadata(metadata);
                    } else {
                        mapper.setDatasetAuthority(datasetId, authoritativeSize,
                                "SHA-256", authoritativeDigest);
                    }
                    valid = true;
                } else {
                    valid = measurement.isVerified()
                            && validMeasurement(measurement, authoritativeSize)
                            && authoritativeDigest.equals(normalizeSha256(measurement.getDigest()));
                }
                message = valid ? "verified against dataset-version authority"
                        : firstText(measurement.getMessage(), "integrity verification failed");
            } catch (RuntimeException error) {
                message = error.getMessage() == null ? "integrity verification failed" : error.getMessage();
            }
            mapper.updateReplicaIntegrity(replica.getReplicaId(),
                    measurement == null ? replica.getSizeBytes() : measurement.getSizeBytes(),
                    measurement == null ? null : measurement.getAlgorithm(),
                    measurement == null ? null : measurement.getDigest(),
                    valid ? "AVAILABLE" : "VERIFY_FAILED", message, valid);
            mapper.updateCandidateIntegrity(replica.getNodeId(), replica.getFilePath(),
                    measurement == null ? replica.getSizeBytes() : measurement.getSizeBytes(),
                    measurement == null ? null : measurement.getAlgorithm(),
                    measurement == null ? null : measurement.getDigest(),
                    valid ? "AVAILABLE" : "VERIFY_FAILED", valid);
            available |= valid;
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
        if (dataset.getVerifiedAt() == null || countUsableReplicas(datasetId) == 0) {
            throw RegistrationException.conflict("DATASET_NO_USABLE_REPLICA",
                    "dataset must have a verified replica on an available node");
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
        RegisteredDataset dataset = requireDataset(datasetId);
        DatasetDiscoveryCandidate candidate = mapper.findCandidateById(candidateId);
        if (candidate == null) throw RegistrationException.notFound("dataset candidate not found");
        if (candidate.getRegisteredDatasetId() != null
                && !datasetId.equals(candidate.getRegisteredDatasetId())) {
            throw RegistrationException.conflict("dataset candidate is already registered");
        }
        if ("MISSING".equals(candidate.getAvailability())) {
            throw RegistrationException.invalid("dataset candidate is missing");
        }
        DatasetReplica existing = mapper.findReplicaByNodePath(candidate.getNodeId(), candidate.getFilePath());
        if (existing != null) {
            if (datasetId.equals(existing.getDatasetId())) return existing;
            throw RegistrationException.conflict("dataset file is already registered");
        }
        DatasetMetadata metadata = mapper.findDatasetMetadata(datasetId);
        String authority = requireAuthority(metadata);
        NodeManagement node = nodeMapper.getNodeById(candidate.getNodeId());
        if (node == null) throw RegistrationException.invalid("candidate node is not registered");
        FileIntegrityResult measurement = uploadClient.verify(node, candidate.getFilePath(),
                metadata.getAuthoritativeSizeBytes(), authority, datasetId,
                dataset.getDatasetVersion(), requestId, null);
        if (!measurement.isVerified()
                || !validMeasurement(measurement, metadata.getAuthoritativeSizeBytes())) {
            mapper.updateCandidateIntegrity(candidate.getNodeId(), candidate.getFilePath(),
                    measurement.getSizeBytes(), measurement.getAlgorithm(), measurement.getDigest(),
                    "VERIFY_FAILED", false);
            throw RegistrationException.invalid("candidate does not match dataset-version authority");
        }
        mapper.updateCandidateIntegrity(candidate.getNodeId(), candidate.getFilePath(),
                measurement.getSizeBytes(), measurement.getAlgorithm(), measurement.getDigest(),
                "AVAILABLE", true);
        DatasetReplica replica = DatasetReplica.builder()
                .datasetId(datasetId)
                .nodeId(candidate.getNodeId())
                .filePath(candidate.getFilePath())
                .sizeBytes(measurement.getSizeBytes())
                .checksumAlgorithm("SHA-256")
                .checksum(measurement.getDigest())
                .availability("AVAILABLE")
                .verificationMessage("verified against dataset-version authority")
                .lastSeenAt(candidate.getLastSeenAt())
                .verifiedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
        mapper.insertReplica(replica);
        mapper.markCandidateRegistered(candidateId, datasetId);
        audit("DATASET", String.valueOf(datasetId), "ADD_REPLICA", requestId, String.valueOf(replica.getReplicaId()));
        return mapper.findReplicaById(replica.getReplicaId());
    }

    public List<DatasetReplica> listReplicas(Long datasetId) {
        requireDataset(datasetId);
        List<DatasetReplica> replicas = mapper.listReplicas(datasetId);
        replicas.forEach(replicaAvailabilityService::enrich);
        return replicas;
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
        int taskReferences = mapper.countTaskReferences(datasetId, dataset.getName());
        if (taskReferences > 0) {
            throw RegistrationException.conflict("DATASET_IN_USE",
                    "数据集被 " + taskReferences + " 个任务引用，无法删除");
        }
        if (mapper.countActiveMigrationReferences(datasetId, dataset.getLegacyDataId()) > 0
                || mapper.countActiveSchedulingReferences(datasetId) > 0) {
            throw RegistrationException.conflict("DATASET_IN_USE", "数据集存在进行中的迁移或调度，无法删除");
        }
        mapper.softDeleteDataset(datasetId);
        audit("DATASET", String.valueOf(datasetId), "UNREGISTER", requestId, null);
    }

    private RegisteredDataset requireDataset(Long datasetId) {
        RegisteredDataset dataset = mapper.findDatasetById(datasetId);
        if (dataset == null) throw RegistrationException.notFound("registered dataset not found");
        return dataset;
    }

    private boolean isEligibleDiscoveryNode(NodeManagement node) {
        if (node == null || node.getVerifiedAt() == null) return false;
        if (!("ONLINE".equals(node.getObservedStatus()) || node.getObservedStatus() == null)) return false;
        String status = node.getRegistrationStatus();
        if (!("REGISTERED".equals(status) || "ACTIVE".equals(status) || "DISABLED".equals(status))) {
            return false;
        }
        String role = node.getType();
        return role != null && ("storage".equalsIgnoreCase(role.trim())
                || "compute-storage".equalsIgnoreCase(role.trim()));
    }

    private void validateQuery(String query) {
        if (query != null && query.length() > 200) {
            throw RegistrationException.invalid("query must not exceed 200 characters");
        }
    }

    private void validateDatasetStatus(String status) {
        if (status == null || status.trim().isEmpty()) return;
        String normalized = status.trim().toUpperCase();
        if (!java.util.Arrays.asList("DRAFT", "VERIFYING", "VERIFY_FAILED", "ACTIVE",
                "DISABLED").contains(normalized)) {
            throw RegistrationException.invalid("unsupported dataset status: " + status);
        }
    }

    private RegisteredDatasetView toView(RegisteredDataset dataset) {
        List<DatasetReplica> replicas = mapper.listReplicas(dataset.getDatasetId());
        int usable = 0;
        String reason = null;
        for (DatasetReplica replica : replicas) {
            replicaAvailabilityService.enrich(replica);
            if ("USABLE".equals(replica.getEffectiveAvailability())) usable++;
            else if (reason == null) reason = replica.getStatusReason();
        }
        RegisteredDatasetView view = RegisteredDatasetView.from(dataset,
                readLabels(dataset.getLabelsJson()), replicas);
        String health = usable == 0 ? "UNAVAILABLE"
                : usable < replicas.size() ? "DEGRADED" : "HEALTHY";
        view.setReplicaHealth(health, usable, replicas.size(),
                usable == replicas.size() ? null : reason);
        return view;
    }

    private int countUsableReplicas(Long datasetId) {
        int count = 0;
        for (DatasetReplica replica : mapper.listReplicas(datasetId)) {
            if (replicaAvailabilityService.evaluate(replica).isUsable()) count++;
        }
        return count;
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

    private void validateUploadRequest(UploadDatasetRequest request, MultipartFile file) {
        if (request == null || request.getNodeId() == null) {
            throw RegistrationException.invalid("nodeId is required");
        }
        applyMetadata(request);
        if (file == null || file.isEmpty()) {
            throw RegistrationException.invalid("dataset file is required and must not be empty");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.trim().isEmpty()) {
            throw RegistrationException.invalid("dataset filename is required");
        }
        if (request.getDataType() == null || request.getDataType().trim().isEmpty()) {
            request.setDataType(inferFileType(originalName));
        }
        if (request.getFormat() == null || request.getFormat().trim().isEmpty()) {
            request.setFormat(request.getDataType());
        }
        validateDatasetMetadata(request.getDatasetCode(), request.getName(),
                request.getVersion(), request.getDataType());
    }

    private void validateDatasetMetadata(String datasetCode, String name,
                                         String version, String dataType) {
        if (datasetCode == null || !DATASET_CODE.matcher(datasetCode).matches()) {
            throw RegistrationException.invalid("datasetCode is invalid");
        }
        if (datasetCode.length() > 128 || ".".equals(datasetCode) || "..".equals(datasetCode)) {
            throw RegistrationException.invalid("datasetCode is invalid");
        }
        if (name == null || name.trim().isEmpty()) {
            throw RegistrationException.invalid("name is required");
        }
        if (name.length() > 255) {
            throw RegistrationException.invalid("name is too long");
        }
        if (version == null || version.length() > 64
                || ".".equals(version) || "..".equals(version)
                || !DATASET_CODE.matcher(version).matches()) {
            throw RegistrationException.invalid("version is invalid");
        }
        if (dataType == null || dataType.trim().isEmpty()) {
            throw RegistrationException.invalid("dataType is required");
        }
    }

    private String uploadRelativePath(UploadDatasetRequest request, MultipartFile file) {
        String extension = safeExtension(file.getOriginalFilename());
        return "uploads/" + request.getDatasetCode() + "/" + request.getVersion()
                + "/" + request.getDatasetCode() + "-" + request.getVersion() + extension;
    }

    private String safeExtension(String originalName) {
        String name = Paths.get(originalName).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) return ".bin";
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,16}") ? "." + extension : ".bin";
    }

    private String inferFileType(String originalName) {
        String extension = safeExtension(originalName);
        return ".bin".equals(extension) ? "BINARY"
                : extension.substring(1).toUpperCase(Locale.ROOT);
    }

    private boolean isStorageRole(String role) {
        return role != null && ("storage".equalsIgnoreCase(role.trim())
                || "compute-storage".equalsIgnoreCase(role.trim()));
    }

    private void applyMetadata(RegisterDatasetRequest request, String metadataJson) {
        if (metadataJson == null || metadataJson.trim().isEmpty()) return;
        try {
            JsonNode root = objectMapper.readTree(metadataJson);
            JsonNode dataset = root.path("dataset");
            if (blank(request.getDatasetCode())) request.setDatasetCode(text(dataset, "datasetCode"));
            if (blank(request.getName())) request.setName(text(dataset, "name"));
            if (blank(request.getVersion())) request.setVersion(text(dataset, "version"));
            if (blank(request.getDescription())) request.setDescription(text(dataset, "description"));
            if (blank(request.getCategory())) request.setCategory(text(dataset, "category"));
            if (blank(request.getFormat())) request.setFormat(text(dataset, "format"));
            if (blank(request.getDataType())) request.setDataType(firstText(request.getFormat()));
            if (request.getLabels() == null && root.path("labels").isObject()) {
                request.setLabels(objectMapper.convertValue(root.path("labels"), STRING_MAP));
            }
            JsonNode resources = root.path("schedulingHints").path("requiredResources");
            if (request.getRequiredResources() == null && resources.isObject()) {
                request.setRequiredResources(resources(resources));
            }
        } catch (JsonProcessingException ex) {
            throw RegistrationException.invalid("invalid dataset metadata JSON");
        }
    }

    private void applyMetadata(UploadDatasetRequest request) {
        if (blank(request.getMetadataJson())) return;
        RegisterDatasetRequest parsed = new RegisterDatasetRequest();
        parsed.setDatasetCode(request.getDatasetCode());
        parsed.setName(request.getName());
        parsed.setVersion(request.getVersion());
        parsed.setDescription(request.getDescription());
        parsed.setDataType(request.getDataType());
        parsed.setCategory(request.getCategory());
        parsed.setFormat(request.getFormat());
        parsed.setLabels(request.getLabels());
        parsed.setRequiredResources(request.getRequiredResources());
        applyMetadata(parsed, request.getMetadataJson());
        request.setDatasetCode(parsed.getDatasetCode());
        request.setName(parsed.getName());
        request.setVersion(parsed.getVersion());
        request.setDescription(parsed.getDescription());
        request.setDataType(parsed.getDataType());
        request.setCategory(parsed.getCategory());
        request.setFormat(parsed.getFormat());
        request.setLabels(parsed.getLabels());
        request.setRequiredResources(parsed.getRequiredResources());
    }

    private void persistDatasetMetadata(Long datasetId, String metadataJson,
                                        RegisterDatasetRequest request,
                                        FileIntegrityResult authority) {
        try {
            JsonNode root = blank(metadataJson) ? objectMapper.createObjectNode()
                    : objectMapper.readTree(metadataJson);
            DatasetMetadata metadata = DatasetMetadata.builder()
                    .datasetId(datasetId)
                    .metadataVersion(firstText(text(root, "metadataVersion"), "1.0"))
                    .authoritativeSizeBytes(authority.getSizeBytes())
                    .digestAlgorithm("SHA-256")
                    .digestValue(normalizeSha256(authority.getDigest()))
                    .schemaJson(json(root.get("schema")))
                    .profileJson(json(root.get("profile")))
                    .sourceJson(json(root.get("source")))
                    .schedulingHintsJson(json(root.get("schedulingHints")))
                    .labelsJson(root.has("labels") ? json(root.get("labels")) : writeJson(request.getLabels()))
                    .build();
            mapper.upsertDatasetMetadata(metadata);
        } catch (JsonProcessingException ex) {
            throw RegistrationException.invalid("invalid dataset metadata JSON");
        }
    }

    private FileIntegrityResult verifyCandidate(NodeManagement node,
                                                DatasetDiscoveryCandidate candidate,
                                                String expectedDigest,
                                                Long datasetId,
                                                String datasetVersion,
                                                String requestId) {
        FileIntegrityResult measurement = uploadClient.verify(node, candidate.getFilePath(),
                candidate.getSizeBytes(), expectedDigest, datasetId,
                datasetVersion, requestId, null);
        boolean valid = validMeasurement(measurement, candidate.getSizeBytes())
                && (expectedDigest == null || (measurement.isVerified()
                    && expectedDigest.equals(normalizeSha256(measurement.getDigest()))));
        mapper.updateCandidateIntegrity(candidate.getNodeId(), candidate.getFilePath(),
                measurement.getSizeBytes(), measurement.getAlgorithm(), measurement.getDigest(),
                valid ? "AVAILABLE" : "VERIFY_FAILED", valid);
        if (!valid) {
            throw RegistrationException.invalid("candidate failed SHA-256 verification");
        }
        return measurement;
    }

    private String metadataDigest(String metadataJson) {
        if (blank(metadataJson)) return null;
        try {
            JsonNode digest = objectMapper.readTree(metadataJson).path("digest");
            String value = text(digest, "value");
            if (blank(value)) return null;
            String algorithm = text(digest, "algorithm");
            if (algorithm == null || !"SHA256".equalsIgnoreCase(algorithm.replace("-", ""))) {
                throw RegistrationException.invalid("dataset digest algorithm must be SHA-256");
            }
            String normalized = normalizeSha256(value);
            if (normalized == null) {
                throw RegistrationException.invalid("dataset SHA-256 digest is invalid");
            }
            return normalized;
        } catch (JsonProcessingException e) {
            throw RegistrationException.invalid("invalid dataset metadata JSON");
        }
    }

    private String requireAuthority(DatasetMetadata metadata) {
        if (metadata == null || metadata.getAuthoritativeSizeBytes() == null
                || !"SHA-256".equalsIgnoreCase(metadata.getDigestAlgorithm())) {
            throw RegistrationException.conflict("DATASET_NOT_STRONGLY_VERIFIED",
                    "dataset version has no authoritative SHA-256");
        }
        String digest = normalizeSha256(metadata.getDigestValue());
        if (digest == null) {
            throw RegistrationException.conflict("DATASET_NOT_STRONGLY_VERIFIED",
                    "dataset version has no authoritative SHA-256");
        }
        return digest;
    }

    private boolean validMeasurement(FileIntegrityResult measurement, Long expectedSize) {
        return measurement != null && "SHA-256".equalsIgnoreCase(measurement.getAlgorithm())
                && normalizeSha256(measurement.getDigest()) != null
                && (expectedSize == null || measurement.getSizeBytes() == expectedSize);
    }

    private String normalizeSha256(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sha256:")) normalized = normalized.substring("sha256:".length());
        return normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private ResourceRequirements resources(JsonNode node) {
        ResourceRequirements result = new ResourceRequirements();
        if (node.has("cpu") && node.get("cpu").isNumber()) result.setCpu(node.get("cpu").doubleValue());
        if (node.has("memoryGi") && node.get("memoryGi").isNumber()) {
            result.setMemoryGi(node.get("memoryGi").doubleValue());
        }
        if (node.has("gpu") && node.get("gpu").isNumber()) result.setGpu(node.get("gpu").doubleValue());
        return result;
    }

    private String json(JsonNode node) throws JsonProcessingException {
        return node == null || node.isMissingNode() || node.isNull() ? null
                : objectMapper.writeValueAsString(node);
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String value = node.get(field).asText();
        return blank(value) ? null : value;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String firstText(String... values) {
        for (String value : values) if (!blank(value)) return value;
        return null;
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
