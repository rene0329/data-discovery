package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.CreateTaskRequest;
import org.example.dto.registration.ResourceRequirements;
import org.example.dto.registration.TaskCreated;
import org.example.dto.registration.TaskExecutionDetail;
import org.example.dto.registration.TaskPreflightCheck;
import org.example.dto.registration.TaskPreflightResult;
import org.example.dto.registration.TaskRunComparison;
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
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
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

    static final String MODE_CENTRALIZED = "CENTRALIZED";
    static final String MODE_IN_PLACE = "IN_PLACE";

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

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public TaskCreated create(CreateTaskRequest request, String requestId) {
        String existingResourceId = auditMapper.findResourceIdByRequest("TASK", "CREATE", requestId);
        if (existingResourceId != null) {
            TaskManagement existing = taskMapper.getTaskByTaskId(Integer.valueOf(existingResourceId));
            return existing == null
                    ? new TaskCreated(Integer.valueOf(existingResourceId), "ACCEPTED")
                    : taskCreated(existing);
        }
        validate(request);
        String executionMode = normalizeExecutionMode(request.getExecutionMode());
        String acceptanceRunId = normalizeAcceptanceRunId(request.getAcceptanceRunId());
        int runRound = request.getRunRound() == null ? 1 : request.getRunRound();
        request.setExecutionMode(executionMode);
        request.setAcceptanceRunId(acceptanceRunId);
        request.setRunRound(runRound);
        DatasetOperationGuard.lock(datasetMapper, request.getDatasetIds());
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
        datasets.forEach(dataset -> DatasetOperationGuard.requireIdle(datasetMapper, dataset));
        validateImages(datasets, request.getRuntimeImageId());

        TaskManagement task = TaskManagement.builder()
                .taskName(request.getTaskName().trim())
                .selectedData(datasets.stream().map(RegisteredDataset::getName).collect(Collectors.toList()).toString())
                .datasetIdsJson(writeJson(request.getDatasetIds()))
                .runtimeImageId(request.getRuntimeImageId())
                .resourceOverridesJson(writeJson(request.getResourceOverrides()))
                .executionMode(executionMode)
                .acceptanceRunId(acceptanceRunId)
                .runRound(runRound)
                .executionEvidenceComplete(false)
                .status("已接收")
                .createTime(LocalDateTime.now())
                .build();
        taskMapper.submitData(task);
        auditMapper.insert("TASK", String.valueOf(task.getTaskId()), "CREATE", "system", requestId,
                writeJson(request));
        DatasetOperationGuard.afterCommit(() -> orchestrator.executeRegisteredTask(task.getTaskId(), request.getDatasetIds(),
                request.getRuntimeImageId(), request.getResourceOverrides(), executionMode));
        return taskCreated(task);
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
        normalizeExecutionMode(request.getExecutionMode());
        if (request.getAcceptanceRunId() != null && request.getAcceptanceRunId().trim().length() > 128) {
            throw RegistrationException.invalid("acceptanceRunId must be at most 128 characters");
        }
        if (request.getRunRound() != null && request.getRunRound() < 1) {
            throw RegistrationException.invalid("runRound must be at least 1");
        }
    }

    public TaskPreflightResult preflight(CreateTaskRequest request) {
        validate(request);
        return preflightValidated(request);
    }

    private TaskPreflightResult preflightValidated(CreateTaskRequest request) {
        String executionMode = normalizeExecutionMode(request.getExecutionMode());
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
        List<NodeManagement> availableNodes = computeNodes == null ? Collections.emptyList() : computeNodes.stream()
                .filter(nodeAvailabilityService::isSchedulable).collect(Collectors.toList());
        long availableComputeNodes = availableNodes.size();
        checks.add(new TaskPreflightCheck("COMPUTE_POOL", null, "计算节点池",
                availableComputeNodes > 0, availableComputeNodes > 0 ? "AVAILABLE" : "UNAVAILABLE",
                availableComputeNodes > 0 ? null : "NO_AVAILABLE_COMPUTE_NODE",
                availableComputeNodes > 0 ? null : "no available compute node"));
        if (MODE_CENTRALIZED.equals(executionMode)) {
            NodeManagement centralNode = centralNodeName.isEmpty()
                    ? null : nodeMapper.getNodeByName(centralNodeName);
            boolean available = !centralNodeName.isEmpty() && centralNode != null
                    && nodeAvailabilityService.isSchedulable(centralNode)
                    && availableNodes.stream().anyMatch(node -> Objects.equals(
                            node.getNodeId(), centralNode.getNodeId()));
            checks.add(new TaskPreflightCheck("CENTRAL_NODE",
                    centralNode == null ? null : String.valueOf(centralNode.getNodeId()), centralNodeName,
                    available, available ? "AVAILABLE" : "UNAVAILABLE",
                    available ? null : "CENTRAL_NODE_NOT_AVAILABLE",
                    available ? null : "central compute node is not available: " + centralNodeName));
        } else {
            // IN_PLACE means "same physical site", not "same node_id": a
            // replica sitting on a storage-only node is fine as long as a
            // schedulable compute node exists in that node's site_code.
            Set<Integer> availableComputeNodeIds = availableNodes.stream()
                    .map(NodeManagement::getNodeId).collect(Collectors.toSet());
            Set<String> availableComputeSites = availableNodes.stream()
                    .map(NodeManagement::getSiteCode).filter(Objects::nonNull).collect(Collectors.toSet());
            for (Long datasetId : request.getDatasetIds()) {
                RegisteredDataset dataset = datasetMapper.findDatasetById(datasetId);
                List<DatasetReplica> replicas = dataset == null || !"ACTIVE".equals(dataset.getStatus())
                        ? Collections.emptyList() : datasetMapper.listReplicas(datasetId);
                boolean hasInPlaceReplica = false;
                List<String> replicaReasons = new ArrayList<>();
                for (DatasetReplica replica : replicas) {
                    ReplicaAvailability usability = replicaAvailabilityService.evaluate(replica);
                    NodeManagement replicaNode = nodeMapper.getNodeById(replica.getNodeId());
                    String nodeLabel = replicaNode == null
                            ? "node " + replica.getNodeId() : replicaNode.getNodeName();
                    if (!usability.isUsable()) {
                        replicaReasons.add(nodeLabel + ": " + usability.getReason());
                        continue;
                    }
                    if (availableComputeNodeIds.contains(replica.getNodeId())) {
                        // Fast path: the replica already sits on an available compute
                        // node, so no site_code lookup is needed at all.
                        hasInPlaceReplica = true;
                        break;
                    }
                    String siteCode = replicaNode == null ? null : replicaNode.getSiteCode();
                    if (siteCode == null) {
                        replicaReasons.add(nodeLabel + ": node has no site_code set, cannot match a compute node");
                        continue;
                    }
                    if (!availableComputeSites.contains(siteCode)) {
                        replicaReasons.add(nodeLabel + ": no available compute node in site '" + siteCode + "'");
                        continue;
                    }
                    hasInPlaceReplica = true;
                    break;
                }
                String reason = hasInPlaceReplica ? null
                        : dataset == null ? "registered dataset not found: " + datasetId
                        : replicaReasons.isEmpty()
                            ? "dataset has no replicas: " + datasetId
                            : "dataset has no usable replica on an available compute node: " + datasetId
                                + " (" + String.join("; ", replicaReasons) + ")";
                checks.add(check("IN_PLACE_DATASET", datasetId,
                        dataset == null ? null : dataset.getName(), hasInPlaceReplica,
                        hasInPlaceReplica ? "AVAILABLE" : "UNAVAILABLE",
                        "DATASET_NO_IN_PLACE_COMPUTE_REPLICA", reason));
            }
        }
        return new TaskPreflightResult(checks, executionMode);
    }

    public TaskExecutionDetail getExecution(Integer taskId) {
        TaskManagement task = taskMapper.getTaskByTaskId(taskId);
        if (task == null) throw RegistrationException.notFound("task not found: " + taskId);
        return new TaskExecutionDetail(task, taskMapper.listExecutionEvents(taskId));
    }

    public TaskRunComparison compareRun(String acceptanceRunId, Integer runRound) {
        String runId = acceptanceRunId == null ? "" : acceptanceRunId.trim();
        if (runId.isEmpty()) throw RegistrationException.invalid("acceptanceRunId is required");
        int round = runRound == null ? 1 : runRound;
        if (round < 1) throw RegistrationException.invalid("runRound must be at least 1");
        List<TaskManagement> tasks = taskMapper.listByAcceptanceRun(runId, round);
        TaskManagement centralized = firstWithMode(tasks, MODE_CENTRALIZED);
        TaskManagement inPlace = firstWithMode(tasks, MODE_IN_PLACE);
        String reason = comparisonFailureReason(centralized, inPlace);
        boolean comparable = reason == null;
        Double ratio = comparable
                ? centralized.getDataPreparationMs().doubleValue() / inPlace.getDataPreparationMs().doubleValue()
                : null;
        return new TaskRunComparison(runId, round,
                centralized == null ? null : centralized.getTaskId(),
                inPlace == null ? null : inPlace.getTaskId(),
                centralized == null ? null : centralized.getDataPreparationMs(),
                inPlace == null ? null : inPlace.getDataPreparationMs(),
                ratio, comparable, reason);
    }

    private TaskManagement firstWithMode(List<TaskManagement> tasks, String mode) {
        if (tasks == null) return null;
        return tasks.stream().filter(task -> mode.equals(task.getExecutionMode())).findFirst().orElse(null);
    }

    private String comparisonFailureReason(TaskManagement centralized, TaskManagement inPlace) {
        if (centralized == null || inPlace == null) return "both CENTRALIZED and IN_PLACE runs are required";
        if (!sameAcceptanceInput(centralized, inPlace)) return "runs do not use the same datasets, image and resources";
        if (!isCompletedWithEvidence(centralized) || !isCompletedWithEvidence(inPlace)) {
            return "both runs must complete with full execution evidence";
        }
        if (centralized.getDataPreparationMs() == null || centralized.getDataPreparationMs() <= 0
                || inPlace.getDataPreparationMs() == null || inPlace.getDataPreparationMs() <= 0) {
            return "both data preparation durations must be greater than zero";
        }
        return null;
    }

    private boolean sameAcceptanceInput(TaskManagement left, TaskManagement right) {
        return Objects.equals(left.getDatasetIdsJson(), right.getDatasetIdsJson())
                && Objects.equals(left.getRuntimeImageId(), right.getRuntimeImageId())
                && Objects.equals(left.getResourceOverridesJson(), right.getResourceOverridesJson());
    }

    private boolean isCompletedWithEvidence(TaskManagement task) {
        return Boolean.TRUE.equals(task.getExecutionEvidenceComplete())
                && ("已完成".equals(task.getStatus()) || "COMPLETED".equals(task.getStatus()));
    }

    private String normalizeExecutionMode(String value) {
        String mode = value == null || value.trim().isEmpty()
                ? MODE_IN_PLACE : value.trim().toUpperCase();
        if (!MODE_CENTRALIZED.equals(mode) && !MODE_IN_PLACE.equals(mode)) {
            throw RegistrationException.invalid("executionMode must be CENTRALIZED or IN_PLACE");
        }
        return mode;
    }

    private String normalizeAcceptanceRunId(String value) {
        return value == null || value.trim().isEmpty()
                ? "run-" + UUID.randomUUID() : value.trim();
    }

    private TaskCreated taskCreated(TaskManagement task) {
        return new TaskCreated(task.getTaskId(), "ACCEPTED", task.getExecutionMode(),
                task.getAcceptanceRunId(), task.getRunRound());
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
