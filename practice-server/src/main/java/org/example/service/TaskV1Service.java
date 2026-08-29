package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.CreateTaskRequest;
import org.example.dto.registration.ResourceRequirements;
import org.example.dto.registration.TaskCreated;
import org.example.dto.registration.TaskPreflightCheck;
import org.example.dto.registration.TaskPreflightResult;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.RuntimeImage;
import org.example.entity.TaskManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Service
public class TaskV1Service {
    private final DatasetRegistrationMapper datasetMapper;
    private final RuntimeImageMapper imageMapper;
    private final TaskManagementMapper taskMapper;
    private final RegistrationAuditMapper auditMapper;
    private final K8sTaskOrchestratorService orchestrator;
    private final ObjectMapper objectMapper;
    private final NodeManagementMapper nodeMapper;
    private final DatasetReplicaAvailabilityService replicaAvailabilityService;
    private final NodeAvailabilityService nodeAvailabilityService;
    private final String centralNodeName;

    public TaskV1Service(DatasetRegistrationMapper datasetMapper,
                         RuntimeImageMapper imageMapper,
                         TaskManagementMapper taskMapper,
                         RegistrationAuditMapper auditMapper,
                         K8sTaskOrchestratorService orchestrator,
                         ObjectMapper objectMapper,
                         NodeManagementMapper nodeMapper,
                         DatasetReplicaAvailabilityService replicaAvailabilityService,
                         NodeAvailabilityService nodeAvailabilityService,
                         @Value("${dispatch.central-node.name:}") String centralNodeName) {
        this.datasetMapper = datasetMapper;
        this.imageMapper = imageMapper;
        this.taskMapper = taskMapper;
        this.auditMapper = auditMapper;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
        this.nodeMapper = nodeMapper;
        this.replicaAvailabilityService = replicaAvailabilityService;
        this.nodeAvailabilityService = nodeAvailabilityService;
        this.centralNodeName = centralNodeName == null ? "" : centralNodeName.trim();
    }

    public TaskCreated create(CreateTaskRequest request, String requestId) {
        String existingResourceId = auditMapper.findResourceIdByRequest("TASK", "CREATE", requestId);
        if (existingResourceId != null) {
            return new TaskCreated(Integer.valueOf(existingResourceId), "ACCEPTED");
        }
        validate(request);
        TaskPreflightResult preflight = preflightValidated(request);
        if (!preflight.isValid()) {
            TaskPreflightCheck failed = preflight.getChecks().stream()
                    .filter(check -> !check.isAvailable()).findFirst().get();
            if ("DATASET_NOT_FOUND".equals(failed.getErrorCode())
                    || "RUNTIME_IMAGE_NOT_FOUND".equals(failed.getErrorCode())) {
                throw RegistrationException.notFound(failed.getErrorCode(), failed.getMessage());
            }
            throw RegistrationException.conflict(failed.getErrorCode(), failed.getMessage());
        }
        List<RegisteredDataset> datasets = request.getDatasetIds().stream()
                .map(this::requireActiveDataset)
                .collect(Collectors.toList());
        validateImages(datasets, request.getRuntimeImageId());

        TaskManagement task = TaskManagement.builder()
                .taskName(request.getTaskName().trim())
                .selectedData(datasets.stream().map(RegisteredDataset::getName).collect(Collectors.toList()).toString())
                .datasetIdsJson(writeJson(request.getDatasetIds()))
                .runtimeImageId(request.getRuntimeImageId())
                .resourceOverridesJson(writeJson(request.getResourceOverrides()))
                .status("已接收")
                .createTime(LocalDateTime.now())
                .build();
        taskMapper.submitData(task);
        auditMapper.insert("TASK", String.valueOf(task.getTaskId()), "CREATE", "system", requestId,
                writeJson(request));
        orchestrator.executeRegisteredTask(task.getTaskId(), request.getDatasetIds(),
                request.getRuntimeImageId(), request.getResourceOverrides());
        return new TaskCreated(task.getTaskId(), "ACCEPTED");
    }

    private RegisteredDataset requireActiveDataset(Long datasetId) {
        RegisteredDataset dataset = datasetMapper.findDatasetById(datasetId);
        if (dataset == null) throw RegistrationException.notFound("registered dataset not found: " + datasetId);
        if (!"ACTIVE".equals(dataset.getStatus())) {
            throw RegistrationException.conflict("dataset is not ACTIVE: " + datasetId);
        }
        boolean usable = datasetMapper.listReplicas(datasetId).stream()
                .anyMatch(replica -> replicaAvailabilityService.evaluate(replica).isUsable());
        if (!usable) {
            throw RegistrationException.conflict("DATASET_NO_USABLE_REPLICA",
                    "dataset has no usable replica on an available node: " + datasetId);
        }
        return dataset;
    }

    private void validateImages(List<RegisteredDataset> datasets, Long explicitImageId) {
        if (explicitImageId != null) {
            requireUsableImage(explicitImageId);
            return;
        }
        for (RegisteredDataset dataset : datasets) {
            if (dataset.getDefaultRuntimeImageId() == null) {
                throw RegistrationException.conflict("dataset has no default runtime image: " + dataset.getDatasetId());
            }
            requireUsableImage(dataset.getDefaultRuntimeImageId());
        }
    }

    private RuntimeImage requireUsableImage(Long imageId) {
        RuntimeImage image = imageMapper.findById(imageId);
        if (image == null) throw RegistrationException.notFound("runtime image not found: " + imageId);
        if (!"READY".equals(image.getStatus()) || !Boolean.TRUE.equals(image.getEnabled())
                || image.getResolvedDigest() == null || image.getResolvedDigest().trim().isEmpty()) {
            throw RegistrationException.conflict("runtime image is not verified and enabled: " + imageId);
        }
        return image;
    }

    private void validate(CreateTaskRequest request) {
        if (request == null) throw RegistrationException.invalid("request body is required");
        if (request.getTaskName() == null || request.getTaskName().trim().isEmpty()) {
            throw RegistrationException.invalid("taskName is required");
        }
        if (request.getDatasetIds() == null || request.getDatasetIds().isEmpty()) {
            throw RegistrationException.invalid("datasetIds must not be empty");
        }
        Set<Long> unique = new HashSet<>(request.getDatasetIds());
        if (unique.size() != request.getDatasetIds().size() || unique.contains(null)) {
            throw RegistrationException.invalid("datasetIds must be unique and non-null");
        }
        ResourceRequirements resources = request.getResourceOverrides();
        if (resources != null && (negative(resources.getCpu()) || negative(resources.getMemoryGi())
                || negative(resources.getGpu()))) {
            throw RegistrationException.invalid("resourceOverrides values must not be negative");
        }
    }

    public TaskPreflightResult preflight(CreateTaskRequest request) {
        validate(request);
        return preflightValidated(request);
    }

    private TaskPreflightResult preflightValidated(CreateTaskRequest request) {
        List<TaskPreflightCheck> checks = new ArrayList<>();
        for (Long datasetId : request.getDatasetIds()) {
            RegisteredDataset dataset = datasetMapper.findDatasetById(datasetId);
            if (dataset == null) {
                checks.add(check("DATASET", datasetId, null, false, "NOT_FOUND",
                        "DATASET_NOT_FOUND", "registered dataset not found: " + datasetId));
                continue;
            }
            if (!"ACTIVE".equals(dataset.getStatus())) {
                checks.add(check("DATASET", datasetId, dataset.getName(), false, dataset.getStatus(),
                        "DATASET_NOT_ACTIVE", "dataset is not ACTIVE: " + datasetId));
                continue;
            }
            List<DatasetReplica> replicas = datasetMapper.listReplicas(datasetId);
            long usable = replicas.stream()
                    .filter(replica -> replicaAvailabilityService.evaluate(replica).isUsable()).count();
            checks.add(check("DATASET", datasetId, dataset.getName(), usable > 0,
                    usable > 0 ? "AVAILABLE" : "UNAVAILABLE", "DATASET_NO_USABLE_REPLICA",
                    usable > 0 ? null : "dataset has no usable replica on an available node: " + datasetId));
        }

        Set<Long> imageIds = new HashSet<>();
        if (request.getRuntimeImageId() != null) imageIds.add(request.getRuntimeImageId());
        else {
            for (Long datasetId : request.getDatasetIds()) {
                RegisteredDataset dataset = datasetMapper.findDatasetById(datasetId);
                if (dataset != null && dataset.getDefaultRuntimeImageId() != null) {
                    imageIds.add(dataset.getDefaultRuntimeImageId());
                } else if (dataset != null) {
                    checks.add(check("RUNTIME_IMAGE", null, null, false, "MISSING",
                            "DATASET_NO_DEFAULT_RUNTIME_IMAGE",
                            "dataset has no default runtime image: " + datasetId));
                }
            }
        }
        for (Long imageId : imageIds) {
            RuntimeImage image = imageMapper.findById(imageId);
            boolean usable = image != null && "READY".equals(image.getStatus())
                    && Boolean.TRUE.equals(image.getEnabled())
                    && image.getResolvedDigest() != null && !image.getResolvedDigest().trim().isEmpty();
            checks.add(check("RUNTIME_IMAGE", imageId, image == null ? null : image.getName(), usable,
                    image == null ? "NOT_FOUND" : image.getStatus(),
                    image == null ? "RUNTIME_IMAGE_NOT_FOUND" : "RUNTIME_IMAGE_NOT_USABLE",
                    usable ? null : "runtime image is not verified and enabled: " + imageId));
        }

        List<NodeManagement> computeNodes = nodeMapper.getComputeCapableNodes();
        long availableComputeNodes = computeNodes == null ? 0 : computeNodes.stream()
                .filter(nodeAvailabilityService::isSchedulable).count();
        checks.add(new TaskPreflightCheck("COMPUTE_POOL", null, "计算节点池",
                availableComputeNodes > 0, availableComputeNodes > 0 ? "AVAILABLE" : "UNAVAILABLE",
                availableComputeNodes > 0 ? null : "NO_AVAILABLE_COMPUTE_NODE",
                availableComputeNodes > 0 ? null : "no available compute node"));
        if (!centralNodeName.isEmpty()) {
            NodeManagement centralNode = nodeMapper.getNodeByName(centralNodeName);
            boolean available = centralNode != null && nodeAvailabilityService.isSchedulable(centralNode);
            checks.add(new TaskPreflightCheck("CENTRAL_NODE",
                    centralNode == null ? null : String.valueOf(centralNode.getNodeId()), centralNodeName,
                    available, available ? "AVAILABLE" : "UNAVAILABLE",
                    available ? null : "CENTRAL_NODE_NOT_AVAILABLE",
                    available ? null : "central compute node is not available: " + centralNodeName));
        }
        return new TaskPreflightResult(checks);
    }

    private TaskPreflightCheck check(String type, Object id, String name, boolean available,
                                     String status, String errorCode, String message) {
        return new TaskPreflightCheck(type, id == null ? null : String.valueOf(id), name,
                available, status, available ? null : errorCode, message);
    }

    private boolean negative(Double value) { return value != null && value < 0; }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON value", e);
        }
    }
}
