package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.scheduling.SchedulableDatasetView;
import org.example.dto.scheduling.SchedulingPageResult;
import org.example.dto.scheduling.SchedulingPlanAccepted;
import org.example.dto.scheduling.SchedulingPlanDetail;
import org.example.dto.scheduling.SchedulingPlanRequest;
import org.example.dto.scheduling.SchedulingReplicaView;
import org.example.entity.DatasetMetadata;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.SchedulingAssignment;
import org.example.entity.SchedulingPlan;
import org.example.entity.TaskManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.example.mapper.TaskManagementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SchedulingService {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
            new TypeReference<Map<String, Object>>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP =
            new TypeReference<Map<String, String>>() { };
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            "USE_IN_PLACE", "COPY_AND_USE", "MOVE_AND_USE", "REMOTE_READ"));
    private static final Set<String> PLAN_STATUSES = new HashSet<>(Arrays.asList(
            "ACCEPTED", "RUNNING", "COMPLETED", "PARTIAL_COMPLETED", "FAILED"));

    private final DatasetRegistrationMapper datasetMapper;
    private final NodeManagementMapper nodeMapper;
    private final SchedulingPlanMapper planMapper;
    private final TaskManagementMapper taskMapper;
    private final DatasetReplicaAvailabilityService replicaAvailabilityService;
    private final NodeAvailabilityService nodeAvailabilityService;
    private final K8sTaskOrchestratorService orchestrator;
    private final ObjectMapper objectMapper;
    private final NetworkTopologyService networkTopologyService;

    public SchedulingService(DatasetRegistrationMapper datasetMapper,
                             NodeManagementMapper nodeMapper,
                             SchedulingPlanMapper planMapper,
                             TaskManagementMapper taskMapper,
                             DatasetReplicaAvailabilityService replicaAvailabilityService,
                             NodeAvailabilityService nodeAvailabilityService,
                             K8sTaskOrchestratorService orchestrator,
                             ObjectMapper objectMapper,
                             NetworkTopologyService networkTopologyService) {
        this.datasetMapper = datasetMapper;
        this.nodeMapper = nodeMapper;
        this.planMapper = planMapper;
        this.taskMapper = taskMapper;
        this.replicaAvailabilityService = replicaAvailabilityService;
        this.nodeAvailabilityService = nodeAvailabilityService;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
        this.networkTopologyService = networkTopologyService;
    }

    public SchedulingPageResult<SchedulableDatasetView> listDatasets(
            String datasetIds, String category, String format, Integer nodeId,
            String label, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw RegistrationException.invalid("page must be >= 1; pageSize must be between 1 and 100");
        }
        Set<Long> requestedIds = parseDatasetIds(datasetIds);
        String[] labelFilter = parseLabel(label);
        List<SchedulableDatasetView> all = new ArrayList<>();
        for (RegisteredDataset dataset : datasetMapper.listDatasets(null, "ACTIVE")) {
            if (!requestedIds.isEmpty() && !requestedIds.contains(dataset.getDatasetId())) continue;
            if (!matches(category, dataset.getCategory())) continue;
            if (!matches(format, firstText(dataset.getDataFormat(), dataset.getDataType()))) continue;
            DatasetMetadata metadata = datasetMapper.findDatasetMetadata(dataset.getDatasetId());
            if (!matchesLabel(labelFilter, dataset, metadata)) continue;
            SchedulableDatasetView view = toSchedulingView(dataset, metadata, nodeId);
            if (view != null) all.add(view);
        }
        int from = (int) Math.min((long) (page - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new SchedulingPageResult<>(new ArrayList<>(all.subList(from, to)), all.size(), page, pageSize);
    }

    public SchedulingPageResult<SchedulingPlan> listPlans(String query, String status, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw RegistrationException.invalid("page must be >= 1; pageSize must be between 1 and 100");
        }
        String search = blank(query) ? null : query.trim();
        String filter = blank(status) ? null : status.trim().toUpperCase(Locale.ROOT);
        if (filter != null && !PLAN_STATUSES.contains(filter)) {
            throw RegistrationException.invalid("unsupported scheduling plan status: " + filter);
        }
        long total = planMapper.countPlans(search, filter);
        long offset = (long) (page - 1) * pageSize;
        List<SchedulingPlan> plans = offset >= total ? Collections.emptyList()
                : planMapper.listPlans(search, filter, offset, pageSize);
        return new SchedulingPageResult<>(plans, total, page, pageSize);
    }

    public SchedulingPlanDetail getPlan(Long planId) {
        SchedulingPlan plan = planMapper.findById(planId);
        if (plan == null) throw RegistrationException.notFound("scheduling plan not found: " + planId);
        return new SchedulingPlanDetail(plan, planMapper.listAssignments(planId));
    }

    @Transactional
    public SchedulingPlanAccepted submit(SchedulingPlanRequest request) {
        validatePlan(request);
        SchedulingPlan existing = planMapper.findByExternalPlanId(request.getExternalPlanId());
        if (existing != null) return accepted(existing);

        List<SchedulingAssignment> assignments = new ArrayList<>();
        List<Long> datasetIds = new ArrayList<>();
        List<String> datasetNames = new ArrayList<>();
        for (SchedulingPlanRequest.Assignment item : request.getAssignments()) {
            RegisteredDataset dataset = datasetMapper.findDatasetById(item.getDatasetId());
            if (dataset == null || !"ACTIVE".equals(dataset.getStatus())) {
                throw RegistrationException.conflict("dataset is not ACTIVE: " + item.getDatasetId());
            }
            DatasetReplica replica = datasetMapper.findReplicaById(item.getReplicaId());
            if (replica == null || !item.getDatasetId().equals(replica.getDatasetId())) {
                throw RegistrationException.invalid("replica does not belong to dataset: " + item.getReplicaId());
            }
            if (!item.getSourceNodeId().equals(replica.getNodeId())) {
                throw RegistrationException.invalid("sourceNodeId does not match replica node");
            }
            if (!replicaAvailabilityService.evaluate(replica).isUsable()) {
                throw RegistrationException.conflict("replica is not available: " + item.getReplicaId());
            }
            NodeManagement target = nodeMapper.getNodeById(item.getTargetNodeId());
            if (target == null || !nodeAvailabilityService.isSchedulable(target)) {
                throw RegistrationException.conflict("target node is not schedulable: " + item.getTargetNodeId());
            }
            String action = item.getAction().trim().toUpperCase();
            if ("USE_IN_PLACE".equals(action) && !item.getSourceNodeId().equals(item.getTargetNodeId())) {
                throw RegistrationException.invalid("USE_IN_PLACE requires sourceNodeId = targetNodeId");
            }
            networkTopologyService.requirePath(item.getSourceNodeId(), item.getTargetNodeId());
            assignments.add(SchedulingAssignment.builder()
                    .datasetId(item.getDatasetId())
                    .replicaId(item.getReplicaId())
                    .sourceNodeId(item.getSourceNodeId())
                    .targetNodeId(item.getTargetNodeId())
                    .action(action)
                    .status("PENDING")
                    .build());
            datasetIds.add(item.getDatasetId());
            datasetNames.add(dataset.getName());
        }

        TaskManagement task = resolveOrCreateTask(request.getTaskId(), datasetIds, datasetNames);
        SchedulingPlan plan = SchedulingPlan.builder()
                .externalPlanId(request.getExternalPlanId().trim())
                .taskId(request.getTaskId().trim())
                .internalTaskId(task.getTaskId())
                .algorithmName(request.getAlgorithm() == null ? null : request.getAlgorithm().getName())
                .algorithmVersion(request.getAlgorithm() == null ? null : request.getAlgorithm().getVersion())
                .status("ACCEPTED")
                .build();
        planMapper.insertPlan(plan);
        for (SchedulingAssignment assignment : assignments) {
            assignment.setPlanId(plan.getPlanId());
            planMapper.insertAssignment(assignment);
        }
        dispatchAfterCommit(plan.getPlanId(), task.getTaskId(), assignments);
        return accepted(plan);
    }

    private void dispatchAfterCommit(Long planId, Integer taskId,
                                     List<SchedulingAssignment> assignments) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            orchestrator.executeExternalPlan(planId, taskId, assignments);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orchestrator.executeExternalPlan(planId, taskId, assignments);
            }
        });
    }

    private SchedulableDatasetView toSchedulingView(RegisteredDataset dataset,
                                                     DatasetMetadata metadata,
                                                     Integer nodeId) {
        List<SchedulingReplicaView> replicas = new ArrayList<>();
        for (DatasetReplica replica : datasetMapper.listReplicas(dataset.getDatasetId())) {
            if (nodeId != null && !nodeId.equals(replica.getNodeId())) continue;
            if (!replicaAvailabilityService.evaluate(replica).isUsable()) continue;
            NodeManagement node = nodeMapper.getNodeById(replica.getNodeId());
            replicas.add(SchedulingReplicaView.builder()
                    .replicaId(replica.getReplicaId())
                    .nodeId(replica.getNodeId())
                    .nodeName(node == null ? null : node.getNodeName())
                    .filePath(replica.getFilePath())
                    .sizeBytes(replica.getSizeBytes())
                    .availability("AVAILABLE")
                    .build());
        }
        if (replicas.isEmpty()) return null;
        Map<String, Object> profile = readObjectMap(metadata == null ? null : metadata.getProfileJson());
        Map<String, Object> schema = readObjectMap(metadata == null ? null : metadata.getSchemaJson());
        Map<String, Object> hints = readObjectMap(metadata == null ? null : metadata.getSchedulingHintsJson());
        if (hints.isEmpty()) hints = defaultHints(dataset);
        Long sizeBytes = longValue(profile.get("sizeBytes"));
        if (sizeBytes == null) sizeBytes = replicas.get(0).getSizeBytes();
        return SchedulableDatasetView.builder()
                .datasetId(dataset.getDatasetId())
                .datasetCode(dataset.getDatasetCode())
                .name(dataset.getName())
                .version(dataset.getDatasetVersion())
                .category(firstText(dataset.getCategory(), "OTHER"))
                .format(firstText(dataset.getDataFormat(), dataset.getDataType(), "NPZ"))
                .sizeBytes(sizeBytes)
                .sampleCount(longValue(profile.get("sampleCount")))
                .schemaSummary(schemaSummary(schema))
                .schedulingHints(hints)
                .replicas(replicas)
                .build();
    }

    private Map<String, Object> schemaSummary(Map<String, Object> schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", schema.containsKey("type") ? schema.get("type") : "TENSOR");
        Object tensorsValue = schema.get("tensors");
        List<?> tensors = tensorsValue instanceof List ? (List<?>) tensorsValue : Collections.emptyList();
        result.put("tensorCount", tensors.size());
        Object primaryShape = null;
        for (Object tensor : tensors) {
            if (!(tensor instanceof Map)) continue;
            Map<?, ?> item = (Map<?, ?>) tensor;
            if (primaryShape == null) primaryShape = item.get("sampleShape");
            if ("FEATURE".equals(String.valueOf(item.get("role")))) {
                primaryShape = item.get("sampleShape");
                break;
            }
        }
        result.put("primarySampleShape", primaryShape == null ? Collections.emptyList() : primaryShape);
        return result;
    }

    private Map<String, Object> defaultHints(RegisteredDataset dataset) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("cpu", dataset.getRequiredCpu());
        resources.put("memoryGi", dataset.getRequiredMemoryGi());
        resources.put("gpu", dataset.getRequiredGpu());
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("accessMode", "READ_ONLY");
        hints.put("splittable", false);
        hints.put("preferredExecutionMode", "ANY");
        hints.put("estimatedReadRatio", 1.0);
        hints.put("requiredResources", resources);
        return hints;
    }

    private TaskManagement resolveOrCreateTask(String externalTaskId, List<Long> datasetIds,
                                                List<String> datasetNames) {
        Integer candidateId = parseInternalTaskId(externalTaskId);
        TaskManagement task = candidateId == null ? null : taskMapper.getTaskByTaskId(candidateId);
        if (task != null) return task;
        task = TaskManagement.builder()
                .taskName(externalTaskId)
                .selectedData(datasetNames.toString())
                .datasetIdsJson(writeJson(datasetIds))
                .status("已接收")
                .createTime(LocalDateTime.now())
                .build();
        taskMapper.submitData(task);
        return task;
    }

    private void validatePlan(SchedulingPlanRequest request) {
        if (request == null || blank(request.getExternalPlanId()) || blank(request.getTaskId())) {
            throw RegistrationException.invalid("externalPlanId and taskId are required");
        }
        if (request.getAssignments() == null || request.getAssignments().isEmpty()) {
            throw RegistrationException.invalid("assignments must not be empty");
        }
        for (SchedulingPlanRequest.Assignment item : request.getAssignments()) {
            if (item == null || item.getDatasetId() == null || item.getReplicaId() == null
                    || item.getSourceNodeId() == null || item.getTargetNodeId() == null
                    || blank(item.getAction()) || !ACTIONS.contains(item.getAction().trim().toUpperCase())) {
                throw RegistrationException.invalid("invalid scheduling assignment");
            }
        }
    }

    private SchedulingPlanAccepted accepted(SchedulingPlan plan) {
        return new SchedulingPlanAccepted(plan.getPlanId(), plan.getExternalPlanId(),
                plan.getTaskId(), plan.getStatus());
    }

    private Set<Long> parseDatasetIds(String value) {
        if (blank(value)) return Collections.emptySet();
        try {
            return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(Long::valueOf).collect(Collectors.toSet());
        } catch (NumberFormatException ex) {
            throw RegistrationException.invalid("datasetIds must be comma separated integers");
        }
    }

    private String[] parseLabel(String value) {
        if (blank(value)) return null;
        int separator = value.indexOf(':');
        if (separator <= 0) throw RegistrationException.invalid("label must use key:value format");
        return new String[] {value.substring(0, separator), value.substring(separator + 1)};
    }

    private boolean matchesLabel(String[] filter, RegisteredDataset dataset, DatasetMetadata metadata) {
        if (filter == null) return true;
        String json = metadata != null && !blank(metadata.getLabelsJson())
                ? metadata.getLabelsJson() : dataset.getLabelsJson();
        try {
            Map<String, String> labels = blank(json) ? Collections.emptyMap()
                    : objectMapper.readValue(json, STRING_MAP);
            return filter[1].equals(labels.get(filter[0]));
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private Map<String, Object> readObjectMap(String json) {
        if (blank(json)) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (JsonProcessingException ex) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid JSON value", ex);
        }
    }

    private Integer parseInternalTaskId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("task-")) normalized = normalized.substring(5);
        try {
            return Integer.valueOf(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean matches(String expected, String actual) {
        return blank(expected) || expected.trim().equalsIgnoreCase(actual == null ? "" : actual.trim());
    }

    private Long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String firstText(String... values) {
        for (String value : values) if (!blank(value)) return value;
        return null;
    }
}
