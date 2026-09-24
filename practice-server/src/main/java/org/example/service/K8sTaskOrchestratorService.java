package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.DataManagement;
import org.example.entity.MigrationTask;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.DatasetReplica;
import org.example.entity.DatasetMetadata;
import org.example.entity.RuntimeImage;
import org.example.entity.SchedulingAssignment;
import org.example.entity.TaskManagement;
import org.example.entity.TaskExecutionEvent;
import org.example.factory.JobCreationResult;
import org.example.factory.K8sJobFactory;
import org.example.mapper.DataManagementMapper;
import org.example.mapper.MigrationTaskMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.RuntimeImageMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.example.vo.DataItemResult;
import org.example.dto.registration.ResourceRequirements;
import org.example.model.FileIntegrityResult;
import org.example.security.access.AccessAuditContext;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.AccessScope;
import org.example.security.access.DatasetAccessAuthorizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * K8s任务编排服务 (最终修正版)
 * - 移除了单例的KubernetesClient
 * - 所有k8s操作都使用从JobFactory获取的、与目标集群匹配的client实例
 */
@Service
@Slf4j
public class K8sTaskOrchestratorService {

    private final DataManagementMapper dataManagementMapper;
    private final NodeManagementMapper nodeManagementMapper;
    private final TaskManagementMapper taskManagementMapper;
    private final MigrationTaskMapper migrationTaskMapper;
    private final K8sJobFactory k8sJobFactory;
    private final DatasetRegistrationMapper datasetRegistrationMapper;
    private final RuntimeImageMapper runtimeImageMapper;
    private final ObjectMapper objectMapper;
    private final String centralNodeName;
    private final String centralNodeIp;
    private final Executor dataProcessingExecutor;
    private final DatasetReplicaAvailabilityService replicaAvailabilityService;
    private final SchedulingPlanMapper schedulingPlanMapper;
    private final DatasetUploadClient datasetUploadClient;
    private final DatasetAccessAuthorizationService accessAuthorizationService;

    private final InPlacePlacementService inPlacePlacement;
    private final DatasetHeatService heat;
    // 【架构修正#1】: 不再需要单例的KubernetesClient，已移除。

    /** 等待单个 K8s Job 完成的超时时间（分钟）。 */
    @Value("${app.orchestrator.job-wait-timeout-minutes:30}")
    private long jobWaitTimeoutMinutes;

    /** Job 失败后的最大重试次数（不含首次执行，1 表示总共最多执行 2 次）。 */
    @Value("${app.orchestrator.job-max-retries:1}")
    private int jobMaxRetries;

    /** 重试前的退避时间（秒）。 */
    @Value("${app.orchestrator.job-retry-backoff-seconds:5}")
    private long jobRetryBackoffSeconds;

    @Value("${app.orchestrator.status-poll-interval-ms:1000}")
    private long statusPollIntervalMs;

    @Autowired
    public K8sTaskOrchestratorService(
            DataManagementMapper dataManagementMapper,
            NodeManagementMapper nodeManagementMapper,
            TaskManagementMapper taskManagementMapper,
            MigrationTaskMapper migrationTaskMapper,
            // 【架构修正#2】: 从构造函数中移除KubernetesClient
            K8sJobFactory k8sJobFactory,
            DatasetRegistrationMapper datasetRegistrationMapper,
            RuntimeImageMapper runtimeImageMapper,
            ObjectMapper objectMapper,
            @Value("${dispatch.central-node.name:}") String centralNodeName,
            @Value("${dispatch.central-node.ip:}") String centralNodeIp,
            @Qualifier("dataProcessingExecutor") Executor dataProcessingExecutor,
            DatasetReplicaAvailabilityService replicaAvailabilityService,
            SchedulingPlanMapper schedulingPlanMapper,
            DatasetUploadClient datasetUploadClient,
            DatasetAccessAuthorizationService accessAuthorizationService,
            InPlacePlacementService inPlacePlacement,
            DatasetHeatService heat
    ) {
        this.dataManagementMapper = dataManagementMapper;
        this.nodeManagementMapper = nodeManagementMapper;
        this.taskManagementMapper = taskManagementMapper;
        this.migrationTaskMapper = migrationTaskMapper;
        this.k8sJobFactory = k8sJobFactory;
        this.datasetRegistrationMapper = datasetRegistrationMapper;
        this.runtimeImageMapper = runtimeImageMapper;
        this.objectMapper = objectMapper;
        this.centralNodeName = centralNodeName;
        this.centralNodeIp = centralNodeIp;
        this.dataProcessingExecutor = dataProcessingExecutor;
        this.replicaAvailabilityService = replicaAvailabilityService;
        this.schedulingPlanMapper = schedulingPlanMapper;
        this.datasetUploadClient = datasetUploadClient;
        this.accessAuthorizationService = accessAuthorizationService;
        this.inPlacePlacement = inPlacePlacement;
        this.heat = heat;
    }


    @Async
    public void executeRegisteredTask(Integer taskId, List<Long> datasetIds,
                                      Long explicitRuntimeImageId,
                                      ResourceRequirements overrides,
                                      String executionMode) {
        final String normalizedMode = normalizeExecutionMode(executionMode);
        log.info("注册资源任务 {} 开始执行，mode={}, datasetIds={}", taskId, normalizedMode, datasetIds);
        TaskManagement running = new TaskManagement();
        running.setTaskId(taskId);
        running.setStatus("执行中");
        running.setStartedAt(LocalDateTime.now(ZoneOffset.UTC));
        running.setExecutionEvidenceComplete(false);
        taskManagementMapper.updateExecutionSummary(running);
        try {
            if (TaskV1Service.MODE_COMPARISON.equals(normalizedMode)) {
                executeComparisonTask(taskId, datasetIds, explicitRuntimeImageId, overrides);
                return;
            }
            Map<Long, InPlacePlacementService.Placement> inPlacePlan = TaskV1Service.MODE_IN_PLACE.equals(normalizedMode)
                    ? planInPlace(datasetIds, overrides) : Collections.<Long, InPlacePlacementService.Placement>emptyMap();
            List<CompletableFuture<DataItemResult>> futures = datasetIds.stream()
                    .map(datasetId -> submitRegisteredDataItem(taskId, datasetId, explicitRuntimeImageId,
                            overrides, normalizedMode, inPlacePlan.get(datasetId)))
                    .collect(Collectors.toList());
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<String> schedules = new ArrayList<>();
            List<DataItemResult> successful = new ArrayList<>();
            Set<Long> readDatasets = new LinkedHashSet<>();
            for (int i = 0; i < futures.size(); i++) {
                DataItemResult result = futures.get(i).get();
                if (result != null) {
                    schedules.add(result.getScheduleT1());
                    successful.add(result);
                    if (hasCompleteEvidence(result)) readDatasets.add(datasetIds.get(i));
                }
            }
            updateRegisteredTaskStatus(taskId, normalizedMode, schedules, successful, datasetIds.size());
            recordDatasetReads(taskId, readDatasets);
        } catch (Exception e) {
            log.error("注册资源任务 {} 执行失败", taskId, e);
            updateTaskStatusToFailed(taskId, e.getMessage());
        }
    }

    /**
     * 对比任务：同一个 taskId 下，每个数据集分别以分布式（IN_PLACE）和集中式（CENTRALIZED）各执行一个 Job。
     * 2N 个 Job 一次性提交到 dataProcessingExecutor 并发执行，与此前两个单模式任务同时运行的行为一致。
     */
    private void executeComparisonTask(Integer taskId, List<Long> datasetIds,
                                       Long explicitRuntimeImageId,
                                       ResourceRequirements overrides) {
        List<CompletableFuture<DataItemResult>> inPlaceFutures = new ArrayList<>();
        List<CompletableFuture<DataItemResult>> centralizedFutures = new ArrayList<>();
        Map<Long, InPlacePlacementService.Placement> inPlacePlan = planInPlace(datasetIds, overrides);
        // 按数据集交替提交两种模式：线程池排队时，两种模式获得同等的启动机会。
        for (Long datasetId : datasetIds) {
            inPlaceFutures.add(submitRegisteredDataItem(taskId, datasetId, explicitRuntimeImageId,
                    overrides, TaskV1Service.MODE_IN_PLACE, inPlacePlan.get(datasetId)));
            centralizedFutures.add(submitRegisteredDataItem(taskId, datasetId, explicitRuntimeImageId,
                    overrides, TaskV1Service.MODE_CENTRALIZED, null));
        }
        List<CompletableFuture<DataItemResult>> all = new ArrayList<>(inPlaceFutures);
        all.addAll(centralizedFutures);
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).join();
        List<DataItemResult> inPlaceResults = inPlaceFutures.stream()
                .map(CompletableFuture::join).collect(Collectors.toList());
        List<DataItemResult> centralizedResults = centralizedFutures.stream()
                .map(CompletableFuture::join).collect(Collectors.toList());
        updateComparisonTaskStatus(taskId, datasetIds, inPlaceResults, centralizedResults);
        // 两种模式各读一次同一数据集，但仍是同一个任务的一次访问。
        Set<Long> readDatasets = new LinkedHashSet<>();
        for (int i = 0; i < datasetIds.size(); i++) {
            DataItemResult inPlace = inPlaceResults.get(i);
            DataItemResult centralized = centralizedResults.get(i);
            if ((inPlace != null && hasCompleteEvidence(inPlace))
                    || (centralized != null && hasCompleteEvidence(centralized))) {
                readDatasets.add(datasetIds.get(i));
            }
        }
        recordDatasetReads(taskId, readDatasets);
    }

    /**
     * 一个任务的分布式放置按数据集顺序一次算完，并在同一份节点资源账本上预留：
     * 前面的数据集占用了某计算节点的资源，后面的数据集就会看到该节点资源不足而改用次优节点。
     */
    private Map<Long, InPlacePlacementService.Placement> planInPlace(List<Long> datasetIds,
                                                                     ResourceRequirements overrides) {
        Map<Long, InPlacePlacementService.Placement> plan = new java.util.HashMap<>();
        List<NodeManagement> computeNodes = inPlacePlacement.schedulableComputeNodes();
        NodeResourceLedger ledger = inPlacePlacement.resourceLedger(computeNodes);
        for (Long datasetId : datasetIds) {
            RegisteredDataset dataset = datasetRegistrationMapper.findDatasetById(datasetId);
            if (dataset == null || !"ACTIVE".equals(dataset.getStatus()) || plan.containsKey(datasetId)) continue;
            plan.put(datasetId, inPlacePlacement.place(datasetRegistrationMapper.listReplicas(datasetId),
                    computeNodes, jobDemand(dataset, overrides), ledger));
        }
        return plan;
    }

    /** 与 processRegisteredDataItem 写入 Job 的 CPU/内存请求一致。 */
    static JobResourceDemand jobDemand(RegisteredDataset dataset, ResourceRequirements overrides) {
        return JobResourceDemand.of(
                overrides != null && overrides.getCpu() != null ? overrides.getCpu() : dataset.getRequiredCpu(),
                overrides != null && overrides.getMemoryGi() != null ? overrides.getMemoryGi() : dataset.getRequiredMemoryGi());
    }

    private CompletableFuture<DataItemResult> submitRegisteredDataItem(Integer taskId, Long datasetId,
                                                                       Long explicitRuntimeImageId,
                                                                       ResourceRequirements overrides,
                                                                       String executionMode,
                                                                       InPlacePlacementService.Placement planned) {
        return CompletableFuture.supplyAsync(
                () -> processRegisteredDataItem(taskId, datasetId, explicitRuntimeImageId,
                        overrides, executionMode, planned),
                dataProcessingExecutor).exceptionally(ex -> {
            log.error("注册数据集 {} ({}) 处理失败: {}", datasetId, executionMode, ex.getMessage());
            return null;
        });
    }

    @Async
    public void executeExternalPlan(Long planId, Integer taskId,
                                    List<SchedulingAssignment> assignments) {
        schedulingPlanMapper.updatePlanStatus(planId, "RUNNING", null);
        // 调度结果页按目标节点展示：每个目标节点一个分组，保持 assignment 提交顺序。
        Map<String, List<String>> schedulesByTarget = new LinkedHashMap<>();
        double totalSeconds = 0.0;
        int successCount = 0;
        String lastError = null;
        Set<Long> readDatasets = new LinkedHashSet<>();
        for (SchedulingAssignment assignment : assignments) {
            List<String> targetLines = schedulesByTarget.computeIfAbsent(
                    nodeLabel(assignment.getTargetNodeId()), key -> new ArrayList<>());
            try {
                schedulingPlanMapper.updateAssignmentStatus(
                        assignment.getAssignmentId(), "RUNNING", null);
                DataItemResult result = processExternalAssignment(taskId, assignment);
                if (result == null) throw new IllegalStateException("assignment execution failed");
                targetLines.add(result.getScheduleT1());
                totalSeconds += result.getT1Seconds();
                successCount++;
                // Job 成功即已读完输入并通过字节数与 SHA-256 校验（validateInputEvidence）。
                readDatasets.add(assignment.getDatasetId());
                schedulingPlanMapper.updateAssignmentStatus(
                        assignment.getAssignmentId(), "COMPLETED", null);
            } catch (Exception ex) {
                lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                log.error("外部调度方案 {} 的 assignment {} 执行失败",
                        planId, assignment.getAssignmentId(), ex);
                targetLines.add(datasetLabel(assignment.getDatasetId()) + ": 执行失败");
                schedulingPlanMapper.updateAssignmentStatus(
                        assignment.getAssignmentId(), "FAILED", lastError);
            }
        }
        String status = successCount == assignments.size() ? "COMPLETED"
                : successCount == 0 ? "FAILED" : "PARTIAL_COMPLETED";
        schedulingPlanMapper.updatePlanStatus(planId, status, lastError);
        List<String> scheduleLines = new ArrayList<>();
        for (Map.Entry<String, List<String>> target : schedulesByTarget.entrySet()) {
            scheduleLines.add("调度目标节点 " + target.getKey() + ":");
            scheduleLines.addAll(target.getValue());
        }
        updateFinalTaskStatus(taskId, totalSeconds, String.join("\n", scheduleLines),
                successCount, assignments.size());
        recordDatasetReads(taskId, readDatasets);
    }

    /**
     * 任务真实读取数据才算一次访问：Job 完整读到输入且字节数、SHA-256 与副本一致后，
     * 每个任务对每个数据集只加一次热度。提交、数据搬运和失败的 Job 都不加热度。
     */
    private void recordDatasetReads(Integer taskId, Set<Long> datasetIds) {
        for (Long datasetId : datasetIds) {
            try {
                heat.recordAccess(datasetId);
                log.info("任务 {} 读取数据集 {} 已计入热度", taskId, datasetId);
            } catch (Exception e) {
                // 热度只影响后续存储决策，记录失败不改变已回写的任务结果。
                log.warn("任务 {} 读取数据集 {} 计入热度失败: {}", taskId, datasetId, e.getMessage());
            }
        }
    }

    /**
     * 外部方案的动作标签：源、目标为同一节点时不发生跨节点搬运，统一记为“原位”。
     */
    static String externalActionLabel(String action, boolean sameNode) {
        if (sameNode) return "原位";
        if ("COPY_AND_USE".equals(action)) return "复制";
        if ("MOVE_AND_USE".equals(action)) return "迁移";
        if ("REMOTE_READ".equals(action)) return "远程读取";
        return action;
    }

    /** 仅用于展示文本；查询失败时退回到 ID，不影响任务状态回写。 */
    private String nodeLabel(Integer nodeId) {
        NodeManagement node = null;
        try {
            node = nodeId == null ? null : nodeManagementMapper.getNodeById(nodeId);
        } catch (Exception e) {
            log.warn("查询节点 {} 名称失败: {}", nodeId, e.getMessage());
        }
        return node == null || node.getNodeName() == null || node.getNodeName().trim().isEmpty()
                ? "节点#" + nodeId : node.getNodeName();
    }

    /** 仅用于展示文本；查询失败时退回到 ID，不影响任务状态回写。 */
    private String datasetLabel(Long datasetId) {
        RegisteredDataset dataset = null;
        try {
            dataset = datasetId == null ? null : datasetRegistrationMapper.findDatasetById(datasetId);
        } catch (Exception e) {
            log.warn("查询数据集 {} 编码失败: {}", datasetId, e.getMessage());
        }
        return datasetLabel(dataset, datasetId);
    }

    private static String datasetLabel(RegisteredDataset dataset, Long datasetId) {
        if (dataset != null && dataset.getDatasetCode() != null && !dataset.getDatasetCode().trim().isEmpty()) {
            return dataset.getDatasetCode();
        }
        if (dataset != null && dataset.getName() != null && !dataset.getName().trim().isEmpty()) {
            return dataset.getName();
        }
        return "数据集#" + datasetId;
    }

    private DataItemResult processExternalAssignment(Integer taskId,
                                                      SchedulingAssignment assignment) {
        RegisteredDataset dataset = datasetRegistrationMapper.findDatasetById(assignment.getDatasetId());
        DatasetReplica replica = datasetRegistrationMapper.findReplicaById(assignment.getReplicaId());
        if (dataset == null || replica == null || !dataset.getDatasetId().equals(replica.getDatasetId())) {
            throw new IllegalStateException("数据集或副本不存在");
        }
        NodeManagement sourceNode = nodeManagementMapper.getNodeById(assignment.getSourceNodeId());
        NodeManagement targetNode = nodeManagementMapper.getNodeById(assignment.getTargetNodeId());
        if (sourceNode == null || targetNode == null) throw new IllegalStateException("源节点或目标节点不存在");
        if (!SchedulingService.isComputeNode(targetNode)) throw new IllegalStateException("目标节点不具备计算能力");
        // Validate the selected image before any transfer or source deletion.
        TaskManagement task = taskManagementMapper.getTaskByTaskId(taskId);
        Long imageId = task != null && task.getRuntimeImageId() != null
                ? task.getRuntimeImageId() : dataset.getDefaultRuntimeImageId();
        RuntimeImage image = imageId == null ? null : runtimeImageMapper.findById(imageId);
        if (image == null || !"READY".equals(image.getStatus()) || !Boolean.TRUE.equals(image.getEnabled())
                || image.getResolvedDigest() == null || image.getResolvedDigest().trim().isEmpty()) {
            throw new IllegalStateException("运行镜像不可用于调度: " + imageId);
        }
        image.setCommand(readStringList(image.getCommandJson()));
        image.setArgsTemplate(readStringList(image.getArgsTemplateJson()));
        NodeManagement executionSource = sourceNode;
        Long executionSize = replica.getSizeBytes();
        String executionChecksumAlgorithm = replica.getChecksumAlgorithm();
        String executionChecksum = replica.getChecksum();
        if (("COPY_AND_USE".equals(assignment.getAction())
                || "MOVE_AND_USE".equals(assignment.getAction()))
                && !sourceNode.getNodeId().equals(targetNode.getNodeId())) {
            DatasetMetadata metadata = datasetRegistrationMapper.findDatasetMetadata(dataset.getDatasetId());
            Long authoritativeSize = requireAuthoritativeSize(metadata);
            String authoritativeSha256 = requireAuthoritativeSha256(metadata);
            DatasetReplica targetReplica = datasetRegistrationMapper.findReplicaByDatasetNodePath(
                    dataset.getDatasetId(), targetNode.getNodeId(), replica.getFilePath());
            if (targetReplica == null) {
                targetReplica = DatasetReplica.builder()
                        .datasetId(dataset.getDatasetId())
                        .nodeId(targetNode.getNodeId())
                        .filePath(replica.getFilePath())
                        .sizeBytes(authoritativeSize)
                        .availability("VERIFYING")
                        .verificationMessage("external plan copy in progress")
                        .lastSeenAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build();
                datasetRegistrationMapper.insertReplica(targetReplica);
            } else {
                datasetRegistrationMapper.updateReplicaAvailability(
                        targetReplica.getReplicaId(), "VERIFYING", false);
            }
            String copyRequestId = "task-" + taskId + "-assignment-" + assignment.getAssignmentId();
            String acceptanceRunId = task == null ? null : task.getAcceptanceRunId();
            FileIntegrityResult copied = datasetUploadClient.copyFrom(
                    sourceNode, targetNode, replica.getFilePath(), authoritativeSize,
                    authoritativeSha256, dataset.getDatasetId(), dataset.getDatasetVersion(),
                    copyRequestId, acceptanceRunId);
            if (!matchesAuthority(copied, authoritativeSize, authoritativeSha256)) {
                if (copied == null) {
                    datasetRegistrationMapper.updateReplicaAvailability(
                            targetReplica.getReplicaId(), "VERIFY_FAILED", false);
                } else {
                    datasetRegistrationMapper.updateReplicaIntegrity(targetReplica.getReplicaId(),
                            copied.getSizeBytes(), copied.getAlgorithm(), copied.getDigest(),
                            "VERIFY_FAILED", "external plan copy does not match dataset-version authority", false);
                }
                throw new IllegalStateException("复制后的副本未通过数据集版本 SHA-256 校验");
            }
            datasetUploadClient.scan(targetNode);
            datasetRegistrationMapper.updateCandidateIntegrity(targetNode.getNodeId(), replica.getFilePath(),
                    copied.getSizeBytes(), "SHA-256", copied.getDigest(), "AVAILABLE", true);
            datasetRegistrationMapper.updateReplicaIntegrity(targetReplica.getReplicaId(),
                    copied.getSizeBytes(), "SHA-256", copied.getDigest(), "AVAILABLE",
                    "verified against dataset-version authority", true);
            executionSource = targetNode;
            executionSize = copied.getSizeBytes();
            executionChecksumAlgorithm = "SHA-256";
            executionChecksum = copied.getDigest();
            if ("MOVE_AND_USE".equals(assignment.getAction())) {
                datasetUploadClient.delete(sourceNode, replica.getFilePath(), dataset.getDatasetId(),
                        dataset.getDatasetVersion(), copyRequestId, acceptanceRunId);
                datasetRegistrationMapper.updateReplicaAvailability(
                        replica.getReplicaId(), "MISSING", false);
            }
        }
        String fileName = replica.getFilePath();
        int slash = fileName == null ? -1 : fileName.lastIndexOf('/');
        if (slash >= 0) fileName = fileName.substring(slash + 1);
        if (fileName == null || fileName.isEmpty()) fileName = dataset.getDatasetCode();
        DataManagement dataInfo = DataManagement.builder()
                .dataId(dataset.getLegacyDataId())
                .dataName(fileName)
                .dataSize(executionSize)
                .dataServer(executionSource.getNodeName())
                .dataNodeId(executionSource.getNodeId())
                .filePath(replica.getFilePath())
                .requiredCpu(dataset.getRequiredCpu())
                .requiredMemory(dataset.getRequiredMemoryGi())
                .contentChecksumAlgorithm(executionChecksumAlgorithm)
                .contentChecksum(executionChecksum)
                .datasetVersion(dataset.getDatasetVersion())
                .build();
        AtomicReference<String> selectedNode = new AtomicReference<>(targetNode.getNodeName());
        long durationMs = executeJobAndMeasureInitContainer(
                taskId, "external", executionSource, targetNode.getNodeName(), null,
                dataInfo, dataset.getDatasetId(), image, dataset.getRequiredGpu(), selectedNode);
        if (durationMs < 0) throw new IllegalStateException("K8s Job 执行失败");

        DataItemResult result = new DataItemResult();
        result.setT1Seconds(durationMs / 1000.0);
        result.setT2Seconds(durationMs / 1000.0);
        result.setPreparationMs(durationMs);
        result.setSourceNodeName(sourceNode.getNodeName());
        result.setTargetNodeName(selectedNode.get());
        result.setScheduleT1(datasetLabel(dataset, dataset.getDatasetId()) + ": " + sourceNode.getNodeName()
                + " -> " + selectedNode.get() + " ["
                + externalActionLabel(assignment.getAction(),
                        sourceNode.getNodeId().equals(targetNode.getNodeId())) + "]");
        return result;
    }



    private DataItemResult processRegisteredDataItem(Integer taskId, Long datasetId,
                                                     Long explicitRuntimeImageId,
                                                     ResourceRequirements overrides,
                                                     String executionMode,
                                                     InPlacePlacementService.Placement planned) {
        RegisteredDataset dataset = datasetRegistrationMapper.findDatasetById(datasetId);
        if (dataset == null || !"ACTIVE".equals(dataset.getStatus())) {
            throw new IllegalStateException("数据集不存在或不再处于 ACTIVE: " + datasetId);
        }
        List<DatasetReplica> replicas = datasetRegistrationMapper.listReplicas(datasetId);
        String targetNodeName;
        DatasetReplica replica;
        String placementNote = null;
        if (TaskV1Service.MODE_IN_PLACE.equals(executionMode)) {
            // Same helper as TaskV1Service preflight: the replica's own compute node, else a
            // compute node in the replica's site, else the nearest reachable compute node in
            // any site (by network latency, then bandwidth).
            InPlacePlacementService.Placement placement = planned != null ? planned : inPlacePlacement.place(replicas);
            if (!placement.isFound()) {
                throw new IllegalStateException("数据集没有可到达计算节点的可用副本: " + datasetId
                        + " (" + String.join("; ", placement.getRejectedReasons()) + ")");
            }
            replica = placement.getReplica();
            targetNodeName = placement.getComputeNode().getNodeName();
            if (placement.getResourceNote() != null) {
                placementNote = placement.getResourceNote() + "，改用次优计算节点";
                log.info("数据集 {} 分布式执行：{} -> {}", datasetId, placementNote, targetNodeName);
            }
            if (placement.getTier() == InPlacePlacementService.Tier.NEAREST) {
                log.info("数据集 {} 分布式执行跨站点回退: {} -> {} ({} ms)", datasetId,
                        placement.getReplicaNode().getNodeName(), targetNodeName,
                        placement.getPath().getLatencyMs());
            }
        } else {
            List<DatasetReplica> usableReplicas = replicas.stream()
                    .filter(item -> replicaAvailabilityService.evaluate(item).isUsable())
                    .collect(Collectors.toList());
            targetNodeName = resolveCentralNodeName();
            NodeManagement central = nodeManagementMapper.getNodeByName(targetNodeName);
            if (central == null) throw new IllegalStateException("中心计算节点不存在: " + targetNodeName);
            replica = usableReplicas.stream()
                    .sorted((left, right) -> Boolean.compare(
                            !central.getNodeId().equals(left.getNodeId()),
                            !central.getNodeId().equals(right.getNodeId())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("数据集没有位于活动节点上的可用副本: " + datasetId));
        }
        NodeManagement sourceNode = nodeManagementMapper.getNodeById(replica.getNodeId());
        if (sourceNode == null) throw new IllegalStateException("副本源节点不存在: " + replica.getNodeId());
        Long imageId = explicitRuntimeImageId != null
                ? explicitRuntimeImageId : dataset.getDefaultRuntimeImageId();
        RuntimeImage image = runtimeImageMapper.findById(imageId);
        if (image == null || !"READY".equals(image.getStatus()) || !Boolean.TRUE.equals(image.getEnabled())
                || image.getResolvedDigest() == null || image.getResolvedDigest().trim().isEmpty()) {
            throw new IllegalStateException("运行镜像不可用于调度: " + imageId);
        }
        image.setCommand(readStringList(image.getCommandJson()));
        image.setArgsTemplate(readStringList(image.getArgsTemplateJson()));

        String fileName = replica.getFilePath();
        int slash = fileName == null ? -1 : fileName.lastIndexOf('/');
        if (slash >= 0) fileName = fileName.substring(slash + 1);
        if (fileName == null || fileName.isEmpty()) fileName = dataset.getDatasetCode();
        DataManagement dataInfo = DataManagement.builder()
                .dataId(dataset.getLegacyDataId())
                .dataName(fileName)
                .dataSize(replica.getSizeBytes())
                .dataServer(sourceNode.getNodeName())
                .dataNodeId(sourceNode.getNodeId())
                .filePath(replica.getFilePath())
                .requiredCpu(overrides != null && overrides.getCpu() != null
                        ? overrides.getCpu() : dataset.getRequiredCpu())
                .requiredMemory(overrides != null && overrides.getMemoryGi() != null
                        ? overrides.getMemoryGi() : dataset.getRequiredMemoryGi())
                .contentChecksumAlgorithm(replica.getChecksumAlgorithm())
                .contentChecksum(replica.getChecksum())
                .datasetVersion(dataset.getDatasetVersion())
                .build();

        AtomicReference<String> selectedNodeOut = new AtomicReference<>(targetNodeName);
        AtomicReference<JobExecutionEvidence> evidenceOut = new AtomicReference<>();
        Double gpu = overrides != null && overrides.getGpu() != null
                ? overrides.getGpu() : dataset.getRequiredGpu();
        String jobType = TaskV1Service.MODE_IN_PLACE.equals(executionMode) ? "in-place" : "centralized";
        long preparationMs = executeJobAndMeasureInitContainer(taskId, jobType, sourceNode,
                targetNodeName, null, dataInfo, datasetId, image, gpu, selectedNodeOut, evidenceOut);
        if (preparationMs < 0 || evidenceOut.get() == null) return null;
        JobExecutionEvidence evidence = evidenceOut.get();
        if (!targetNodeName.equals(evidence.nodeName)) {
            throw new IllegalStateException("Job 实际节点与指定模式不一致: expected="
                    + targetNodeName + ", actual=" + evidence.nodeName);
        }

        DataItemResult result = new DataItemResult();
        result.setT1Seconds(preparationMs / 1000.0);
        result.setPreparationMs(preparationMs);
        result.setSourceNodeName(sourceNode.getNodeName());
        result.setTargetNodeName(selectedNodeOut.get());
        result.setScheduleT1(dataset.getDatasetCode() + ": " + sourceNode.getNodeName()
                + " -> " + selectedNodeOut.get() + " [" + executionMode + "]"
                + (placementNote == null ? "" : "（" + placementNote + "）"));
        result.setPlacementNote(placementNote);
        result.setPreparationStartedAt(evidence.preparationStartedAt);
        result.setPreparationReadyAt(evidence.preparationReadyAt);
        result.setComputeStartedAt(evidence.computeStartedAt);
        result.setComputeFinishedAt(evidence.computeFinishedAt);
        result.setActualNodeName(evidence.nodeName);
        result.setInputBytes(evidence.inputBytes);
        result.setInputChecksumSha256(evidence.inputChecksumSha256);
        result.setOutputChecksumSha256(evidence.outputChecksumSha256);
        return result;
    }

    /**
     * 数据集存在多个可用副本时，优先从具备计算能力的节点读取。这样亲和性调度可以直接在
     * 数据所在节点运行，避免仅因 replica_id 较小而从纯存储节点重复搬运已有数据。
     * 同一优先级内保留数据库返回顺序，保证选择稳定。
     */
    static Optional<DatasetReplica> selectPreferredSourceReplica(List<DatasetReplica> usableReplicas,
                                                                  Set<Integer> computeNodeIds) {
        if (usableReplicas == null || usableReplicas.isEmpty()) {
            return Optional.empty();
        }
        Set<Integer> preferredNodes = computeNodeIds == null ? Collections.emptySet() : computeNodeIds;
        return usableReplicas.stream()
                .filter(replica -> replica != null)
                .sorted((left, right) -> Boolean.compare(
                        !preferredNodes.contains(left.getNodeId()),
                        !preferredNodes.contains(right.getNodeId())))
                .findFirst();
    }

    private List<String> readStringList(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("运行镜像命令 JSON 无效", e);
        }
    }

    /**
     * 节点名已显式配置时直接用于 K8s 调度，并用 IP 对节点表做一致性检查。
     * 节点同步尚未完成时允许继续使用显式节点名；未配置节点名时才按 IP 解析。
     */
    private String resolveCentralNodeName() {
        String configuredName = centralNodeName == null ? "" : centralNodeName.trim();
        String configuredIp = centralNodeIp == null ? "" : centralNodeIp.trim();

        if (!configuredName.isEmpty()) {
            if (!configuredIp.isEmpty()) {
                Integer nodeId = nodeManagementMapper.getNodeIdByIp(configuredIp);
                NodeManagement node = nodeId == null ? null : nodeManagementMapper.getNodeById(nodeId);
                String discoveredName = node == null || node.getNodeName() == null
                        ? ""
                        : node.getNodeName().trim();
                if (!discoveredName.isEmpty() && !configuredName.equals(discoveredName)) {
                    throw new IllegalStateException("中心节点配置不一致：IP " + configuredIp
                            + " 在 node_management 中对应 " + discoveredName
                            + "，但配置的节点名是 " + configuredName);
                }
                if (discoveredName.isEmpty()) {
                    log.warn("中心节点 IP {} 尚未同步到 node_management，暂按显式节点名 {} 调度",
                            configuredIp, configuredName);
                }
            }
            return configuredName;
        }

        if (!configuredIp.isEmpty()) {
            Integer nodeId = nodeManagementMapper.getNodeIdByIp(configuredIp);
            if (nodeId != null) {
                NodeManagement node = nodeManagementMapper.getNodeById(nodeId);
                if (node != null && node.getNodeName() != null && !node.getNodeName().trim().isEmpty()) {
                    return node.getNodeName();
                }
            }
            throw new IllegalStateException("找不到中心节点 IP " + configuredIp + " 对应的 node_management 记录");
        }
        throw new IllegalStateException("未配置 dispatch.central-node.ip 或 dispatch.central-node.name");
    }

    /**
     * 【架构修正#3】: 核心方法重构。现在使用从Factory获取的、与Job匹配的正确客户端。
     */
    private long executeJobAndMeasureInitContainer(Integer taskId,
                                                   String type,
                                                   NodeManagement sourceNodeInfo,
                                                   String targetNode,
                                                   String excludedTargetNode,
                                                   DataManagement dataInfo,
                                                   Long registeredDatasetId,
                                                   RuntimeImage runtimeImage,
                                                   Double gpuRequest,
                                                   AtomicReference<String> selectedNodeOut) {
        return executeJobAndMeasureInitContainer(taskId, type, sourceNodeInfo, targetNode,
                excludedTargetNode, dataInfo, registeredDatasetId, runtimeImage, gpuRequest,
                selectedNodeOut, null);
    }

    private long executeJobAndMeasureInitContainer(Integer taskId,
                                                   String type,
                                                   NodeManagement sourceNodeInfo,
                                                   String targetNode,
                                                   String excludedTargetNode,
                                                   DataManagement dataInfo,
                                                   Long registeredDatasetId,
                                                   RuntimeImage runtimeImage,
                                                   Double gpuRequest,
                                                   AtomicReference<String> selectedNodeOut,
                                                   AtomicReference<JobExecutionEvidence> evidenceOut) {
        String dataNameForJob = dataInfo.getDataName() == null
                ? "dataset"
                : dataInfo.getDataName().toLowerCase().replace("_", "-");
        int maxRetries = Math.max(0, jobMaxRetries);
        int totalAttempts = maxRetries + 1;
        long waitTimeoutMinutes = Math.max(1L, jobWaitTimeoutMinutes);
        long retryBackoffSeconds = Math.max(0L, jobRetryBackoffSeconds);
        String lastError = "unknown error";
        MigrationTask migrationTask = null;
        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            String jobName = String.format("%s-%s-%s", type, dataNameForJob, UUID.randomUUID().toString().substring(0, 8));
            KubernetesClient client = null;
            boolean jobSubmitted = false;
            boolean cleanupOnExit = true;
            String accessToken = null;
            try {
                log.info("准备Job: {} (源: {}, 目标: {}, attempt={}/{})",
                        jobName, sourceNodeInfo.getNodeName(), targetNode, attempt + 1, totalAttempts);

                accessToken = issueReadToken(taskId, jobName, registeredDatasetId,
                        dataInfo, sourceNodeInfo);

                JobCreationResult jobResult = k8sJobFactory.createDataProcessingJob(
                        jobName,
                        sourceNodeInfo.getNodeName(),
                        dataInfo.getDataName(),
                        dataInfo.getFilePath(),
                        targetNode,
                        excludedTargetNode,
                        dataInfo.getRequiredCpu(),
                        dataInfo.getRequiredMemory(),
                        gpuRequest,
                        runtimeImage,
                        accessToken);

                client = jobResult.getClient();
                Job job = jobResult.getJob();
                String selectedTargetNodeName = jobResult.getSelectedNodeName();

                NodeManagement targetNodeInfo = nodeManagementMapper.getNodeByName(selectedTargetNodeName);

                if (migrationTask == null) {
                    // 记录迁移任务：PLANNED -> COPYING -> VERIFYING -> SWITCHING -> COMPLETED/FAILED
                    migrationTask = MigrationTask.builder()
                            .taskId(taskId)
                            .dataId(dataInfo.getDataId())
                            .registeredDatasetId(registeredDatasetId)
                            .sourceNodeId(sourceNodeInfo.getNodeId())
                            .targetNodeId(targetNodeInfo != null ? targetNodeInfo.getNodeId() : sourceNodeInfo.getNodeId())
                            .status("COPYING")
                            .retryCount(attempt)
                            .startedAt(LocalDateTime.now())
                            .build();
                    migrationTaskMapper.insert(migrationTask);
                } else {
                    migrationTask.setStatus("COPYING");
                    migrationTask.setRetryCount(attempt);
                    migrationTask.setStartedAt(LocalDateTime.now());
                    migrationTaskMapper.updateLifecycle(migrationTask);
                }

                client.batch().v1().jobs().inNamespace("default").create(job);
                jobSubmitted = true;

                InitContainerEvidence initEvidence = waitForInitContainerEvidence(
                        client, jobName, "data-transfer-container", waitTimeoutMinutes);
                log.info("Job {} 在集群 {} 中已取得传输时间 {}ms",
                        jobName, getClusterIdFromClient(client), initEvidence.durationMs);
                validateInputEvidence(dataInfo, initEvidence);
                if (registeredDatasetId != null) {
                    persistPreparationEvents(taskId, registeredDatasetId, type, jobName,
                            jobResult.getInputPath(), initEvidence, attempt);
                }

                migrationTask.setStatus("VERIFYING");
                migrationTaskMapper.updateLifecycle(migrationTask);

                ProcessingEvidence processingEvidence = waitForJobCompletion(
                        client, jobName, waitTimeoutMinutes);
                if (registeredDatasetId != null) {
                    persistComputeEvents(taskId, registeredDatasetId, type, jobName,
                            jobResult.getInputPath(), processingEvidence, attempt);
                }
                // Explicit external plans remain visible until the Job TTL expires.
                if ("external".equals(type)) {
                    cleanupOnExit = false;
                }

                // data_server 不在此处更新：亲和性调度只是临时将数据下载到 emptyDir 进行训练，
                // 并未持久化到目标节点，data_server 应始终反映数据文件真实所在的节点。

                migrationTask.setStatus("COMPLETED");
                migrationTask.setRetryCount(attempt);
                migrationTask.setFinishedAt(LocalDateTime.now());
                migrationTaskMapper.updateLifecycle(migrationTask);

                if (selectedNodeOut != null) selectedNodeOut.set(selectedTargetNodeName);
                if (evidenceOut != null) {
                    evidenceOut.set(new JobExecutionEvidence(initEvidence.startedAt,
                            initEvidence.finishedAt, processingEvidence.startedAt,
                            processingEvidence.finishedAt, processingEvidence.nodeName,
                            initEvidence.inputBytes, initEvidence.inputChecksumSha256,
                            processingEvidence.outputChecksumSha256));
                }
                return initEvidence.durationMs;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                lastError = "retry backoff interrupted";
                log.error("Job {} 重试退避等待被中断", jobName, ie);
                if (migrationTask != null) {
                    migrationTask.setStatus("FAILED");
                    migrationTask.setRetryCount(attempt);
                    migrationTask.setErrorMessage(lastError);
                    migrationTask.setFinishedAt(LocalDateTime.now());
                    migrationTaskMapper.updateLifecycle(migrationTask);
                }
                return -1;
            } catch (Exception e) {
                lastError = redactSecret(
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                        accessToken);
                if (registeredDatasetId != null) {
                    persistFailureEvent(taskId, registeredDatasetId, type, jobName,
                            selectedNodeOut == null ? null : selectedNodeOut.get(), attempt, lastError);
                }
                String clusterId = client != null ? getClusterIdFromClient(client) : "unknown-context";
                if (attempt < maxRetries) {
                    log.warn("执行Job {}（集群 {}）第 {}/{} 次出现异常，将重试: {}",
                            jobName, clusterId, attempt + 1, totalAttempts, lastError);
                    if (migrationTask != null) {
                        migrationTask.setStatus("RETRYING");
                        migrationTask.setRetryCount(attempt + 1);
                        migrationTask.setErrorMessage(lastError);
                        migrationTaskMapper.updateLifecycle(migrationTask);
                    }
                    try {
                        if (retryBackoffSeconds > 0) {
                            TimeUnit.SECONDS.sleep(retryBackoffSeconds);
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Job {} 重试退避等待被中断", jobName, ie);
                        if (migrationTask != null) {
                            migrationTask.setStatus("FAILED");
                            migrationTask.setRetryCount(attempt);
                            migrationTask.setErrorMessage("retry backoff interrupted");
                            migrationTask.setFinishedAt(LocalDateTime.now());
                            migrationTaskMapper.updateLifecycle(migrationTask);
                        }
                        return -1;
                    }
                    continue;
                }

                log.error("执行Job {}（集群 {}）已达到最大重试次数，最终失败: {}",
                        jobName, clusterId, lastError);
                if (migrationTask != null) {
                    migrationTask.setStatus("FAILED");
                    migrationTask.setRetryCount(attempt);
                    migrationTask.setErrorMessage(lastError);
                    migrationTask.setFinishedAt(LocalDateTime.now());
                    migrationTaskMapper.updateLifecycle(migrationTask);
                }
                return -1;
            } finally {
                if (client != null && jobSubmitted && cleanupOnExit) {
                    cleanupJob(client, jobName);
                }
            }
        }

        log.error("数据项[{}] 迁移任务执行失败，最终错误: {}", dataInfo.getDataName(), lastError);
        return -1;
    }

    private String issueReadToken(Integer taskId, String jobName, Long registeredDatasetId,
                                  DataManagement dataInfo, NodeManagement sourceNode) {
        String absolutePath = dataInfo.getFilePath();
        if (absolutePath == null || !absolutePath.startsWith("/")) {
            throw new IllegalStateException("任务输入缺少绝对源路径，无法签发节点 READ token");
        }
        String datasetIdentifier = registeredDatasetId != null
                ? String.valueOf(registeredDatasetId)
                : "legacy-" + (dataInfo.getDataId() == null ? dataInfo.getDataName() : dataInfo.getDataId());
        String datasetVersion = dataInfo.getDatasetVersion() == null
                || dataInfo.getDatasetVersion().trim().isEmpty()
                ? "unversioned" : dataInfo.getDatasetVersion().trim();
        TaskManagement task = taskManagementMapper.getTaskByTaskId(taskId);
        String runId = task == null ? null : task.getAcceptanceRunId();
        AccessAuthorizationResult grant = accessAuthorizationService.issueInternal(
                new AccessScope(datasetIdentifier, datasetVersion, absolutePath,
                        "READ", sourceNode.getNodeName()),
                new AccessAuditContext("task-" + taskId + "-" + jobName, runId, null));
        if (grant == null || grant.getToken() == null || grant.getToken().trim().isEmpty()) {
            throw new IllegalStateException("节点 READ token 签发失败");
        }
        return grant.getToken();
    }

    /**
     * 【架构修正#4】: 方法增加一个client参数，以确保从正确的集群获取Pod信息。
     */
    private InitContainerEvidence waitForInitContainerEvidence(KubernetesClient client,
                                                               String jobName,
                                                               String initContainerName,
                                                               long timeoutMinutes) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes);
        long pollMs = Math.max(200L, statusPollIntervalMs);

        while (System.nanoTime() < deadlineNanos) {
            List<Pod> pods = client.pods().inNamespace("default")
                    .withLabel("job-name", jobName).list().getItems();

            for (Pod pod : pods) {
                ContainerStateTerminated terminated = findTerminatedInitContainer(pod, initContainerName);
                if (terminated == null) continue;
                if (terminated.getExitCode() == null || terminated.getExitCode() != 0) {
                    throw new IllegalStateException("Job " + jobName + " 的数据准备容器失败，exitCode="
                            + terminated.getExitCode());
                }
                String logs = readContainerLog(client, pod, initContainerName);
                Long duration = parseLongMetric(logs, "TRANSFER_MS=");
                Instant startedAt = parseInstant(terminated.getStartedAt());
                Instant finishedAt = parseInstant(terminated.getFinishedAt());
                if (duration == null && startedAt != null && finishedAt != null) {
                    duration = Duration.between(startedAt, finishedAt).toMillis();
                }
                if (duration == null || duration < 0) {
                    throw new IllegalStateException("Job " + jobName + " 缺少有效的数据准备耗时");
                }
                if (startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)) {
                    throw new IllegalStateException("Job " + jobName + " 缺少有效的数据准备起止时间");
                }
                Long inputBytes = parseLongMetric(logs, "INPUT_BYTES=");
                String checksum = parseStringMetric(logs, "INPUT_SHA256=");
                if (inputBytes == null || inputBytes < 0 || checksum == null
                        || !checksum.matches("[0-9a-fA-F]{64}")) {
                    throw new IllegalStateException("Job " + jobName + " 缺少完整输入字节数或 SHA-256 证据");
                }
                return new InitContainerEvidence(duration, startedAt, finishedAt,
                        podName(pod), podNodeName(pod), inputBytes, checksum.toLowerCase());
            }

            Job currentJob = client.batch().v1().jobs().inNamespace("default").withName(jobName).get();
            if (hasJobCondition(currentJob, "Failed")) {
                throw new IllegalStateException("Job " + jobName + " 在数据传输阶段失败");
            }
            TimeUnit.MILLISECONDS.sleep(pollMs);
        }
        throw new IllegalStateException("等待 Job " + jobName + " 的数据传输阶段超时（"
                + timeoutMinutes + " 分钟）");
    }

    private ProcessingEvidence waitForJobCompletion(KubernetesClient client,
                                                    String jobName,
                                                    long timeoutMinutes) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes);
        long pollMs = Math.max(200L, statusPollIntervalMs);
        while (System.nanoTime() < deadlineNanos) {
            Job job = client.batch().v1().jobs().inNamespace("default").withName(jobName).get();
            if (hasJobCondition(job, "Failed")) {
                throw new IllegalStateException("Job " + jobName + " 的处理容器执行失败");
            }
            List<Pod> pods = client.pods().inNamespace("default")
                    .withLabel("job-name", jobName).list().getItems();
            for (Pod pod : pods) {
                ContainerStateTerminated terminated = findTerminatedContainer(pod, "processing-container");
                if (terminated == null) continue;
                if (terminated.getExitCode() == null || terminated.getExitCode() != 0) {
                    throw new IllegalStateException("Job " + jobName + " 的处理容器执行失败，exitCode="
                            + terminated.getExitCode());
                }
                if (!hasJobCondition(job, "Complete")) continue;
                Instant startedAt = parseInstant(terminated.getStartedAt());
                Instant finishedAt = parseInstant(terminated.getFinishedAt());
                if (startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)) {
                    throw new IllegalStateException("Job " + jobName + " 缺少有效的计算起止时间");
                }
                String output = readContainerLog(client, pod, "processing-container");
                String summary = output.length() <= 4096
                        ? output : output.substring(output.length() - 4096);
                return new ProcessingEvidence(startedAt, finishedAt, podName(pod),
                        podNodeName(pod), summary, sha256Hex(output));
            }
            TimeUnit.MILLISECONDS.sleep(pollMs);
        }
        throw new IllegalStateException("等待 Job " + jobName + " 完成超时（" + timeoutMinutes + " 分钟）");
    }

    private boolean hasJobCondition(Job job, String type) {
        if (job == null || job.getStatus() == null || job.getStatus().getConditions() == null) {
            return false;
        }
        for (JobCondition condition : job.getStatus().getConditions()) {
            if (type.equals(condition.getType()) && "True".equalsIgnoreCase(condition.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private ContainerStateTerminated findTerminatedInitContainer(Pod pod, String initContainerName) {
        if (pod == null || pod.getStatus() == null || pod.getStatus().getInitContainerStatuses() == null) {
            return null;
        }
        for (ContainerStatus status : pod.getStatus().getInitContainerStatuses()) {
            if (initContainerName.equals(status.getName()) && status.getState() != null) {
                return status.getState().getTerminated();
            }
        }
        return null;
    }

    private ContainerStateTerminated findTerminatedContainer(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return null;
        }
        for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
            if (containerName.equals(status.getName()) && status.getState() != null) {
                return status.getState().getTerminated();
            }
        }
        return null;
    }

    private String readContainerLog(KubernetesClient client, Pod pod, String containerName) {
        String name = podName(pod);
        try {
            String value = client.pods().inNamespace("default").withName(name)
                    .inContainer(containerName).getLog();
            return value == null ? "" : value;
        } catch (Exception e) {
            throw new IllegalStateException("读取容器日志失败: pod=" + name
                    + ", container=" + containerName, e);
        }
    }

    private Long parseLongMetric(String logs, String prefix) {
        String value = parseStringMetric(logs, prefix);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String parseStringMetric(String logs, String prefix) {
        if (logs == null) return null;
        for (String line : logs.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) return trimmed.substring(prefix.length()).trim();
        }
        return null;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String podName(Pod pod) {
        return pod == null || pod.getMetadata() == null ? null : pod.getMetadata().getName();
    }

    private String podNodeName(Pod pod) {
        return pod == null || pod.getSpec() == null ? null : pod.getSpec().getNodeName();
    }

    private void validateInputEvidence(DataManagement dataInfo, InitContainerEvidence evidence) {
        if (dataInfo.getDataSize() != null && dataInfo.getDataSize() >= 0
                && !dataInfo.getDataSize().equals(evidence.inputBytes)) {
            throw new IllegalStateException("完整输入字节数不一致: expected=" + dataInfo.getDataSize()
                    + ", actual=" + evidence.inputBytes);
        }
        if ("SHA-256".equalsIgnoreCase(dataInfo.getContentChecksumAlgorithm())
                && dataInfo.getContentChecksum() != null
                && !dataInfo.getContentChecksum().equalsIgnoreCase(evidence.inputChecksumSha256)) {
            throw new IllegalStateException("输入 SHA-256 与已验证副本不一致");
        }
    }

    private Long requireAuthoritativeSize(DatasetMetadata metadata) {
        if (metadata == null || metadata.getAuthoritativeSizeBytes() == null
                || metadata.getAuthoritativeSizeBytes() < 0) {
            throw new IllegalStateException("数据集版本缺少权威字节数");
        }
        return metadata.getAuthoritativeSizeBytes();
    }

    private String requireAuthoritativeSha256(DatasetMetadata metadata) {
        if (metadata == null || !"SHA-256".equalsIgnoreCase(metadata.getDigestAlgorithm())) {
            throw new IllegalStateException("数据集版本缺少权威 SHA-256");
        }
        String digest = normalizeSha256(metadata.getDigestValue());
        if (digest == null) throw new IllegalStateException("数据集版本缺少权威 SHA-256");
        return digest;
    }

    private boolean matchesAuthority(FileIntegrityResult result, Long expectedSize, String expectedSha256) {
        return result != null && result.isVerified() && expectedSize != null
                && result.getSizeBytes() == expectedSize
                && "SHA-256".equalsIgnoreCase(result.getAlgorithm())
                && expectedSha256.equals(normalizeSha256(result.getDigest()));
    }

    private String normalizeSha256(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private void persistPreparationEvents(Integer taskId, Long datasetId, String type,
                                          String jobName, String inputPath,
                                          InitContainerEvidence evidence, int attempt) {
        taskManagementMapper.insertExecutionEvent(TaskExecutionEvent.builder()
                .taskId(taskId).datasetId(datasetId).executionMode(eventMode(type))
                .eventType("DATA_PREPARATION_STARTED").occurredAt(toLocalDateTime(evidence.startedAt))
                .jobName(jobName).podName(evidence.podName).nodeName(evidence.nodeName)
                .inputPath(inputPath).attempt(attempt + 1).build());
        taskManagementMapper.insertExecutionEvent(TaskExecutionEvent.builder()
                .taskId(taskId).datasetId(datasetId).executionMode(eventMode(type))
                .eventType("DATA_PREPARATION_READY").occurredAt(toLocalDateTime(evidence.finishedAt))
                .jobName(jobName).podName(evidence.podName).nodeName(evidence.nodeName)
                .inputPath(inputPath).bytesProcessed(evidence.inputBytes)
                .checksumSha256(evidence.inputChecksumSha256).durationMs(evidence.durationMs)
                .attempt(attempt + 1).build());
    }

    private void persistComputeEvents(Integer taskId, Long datasetId, String type,
                                      String jobName, String inputPath,
                                      ProcessingEvidence evidence, int attempt) {
        taskManagementMapper.insertExecutionEvent(TaskExecutionEvent.builder()
                .taskId(taskId).datasetId(datasetId).executionMode(eventMode(type))
                .eventType("COMPUTE_STARTED").occurredAt(toLocalDateTime(evidence.startedAt))
                .jobName(jobName).podName(evidence.podName).nodeName(evidence.nodeName)
                .inputPath(inputPath).attempt(attempt + 1).build());
        taskManagementMapper.insertExecutionEvent(TaskExecutionEvent.builder()
                .taskId(taskId).datasetId(datasetId).executionMode(eventMode(type))
                .eventType("COMPUTE_COMPLETED").occurredAt(toLocalDateTime(evidence.finishedAt))
                .jobName(jobName).podName(evidence.podName).nodeName(evidence.nodeName)
                .inputPath(inputPath)
                .durationMs(Math.max(0L, Duration.between(evidence.startedAt, evidence.finishedAt).toMillis()))
                .outputSummary(evidence.outputSummary)
                .outputChecksumSha256(evidence.outputChecksumSha256)
                .attempt(attempt + 1).build());
    }

    private void persistFailureEvent(Integer taskId, Long datasetId, String type, String jobName,
                                     String nodeName, int attempt, String error) {
        taskManagementMapper.insertExecutionEvent(TaskExecutionEvent.builder()
                .taskId(taskId).datasetId(datasetId).executionMode(eventMode(type))
                .eventType("JOB_FAILED").occurredAt(LocalDateTime.now(ZoneOffset.UTC))
                .jobName(jobName).nodeName(nodeName).attempt(attempt + 1)
                .detailsJson(writeDetails(error)).build());
    }

    private String writeDetails(String error) {
        try {
            return objectMapper.writeValueAsString(Collections.singletonMap("error", error));
        } catch (Exception ignored) {
            return "{\"error\":\"execution failed\"}";
        }
    }

    private String redactSecret(String message, String secret) {
        if (message == null) return "execution failed";
        if (secret == null || secret.isEmpty()) return message;
        return message.replace(secret, "[REDACTED]");
    }

    private String eventMode(String type) {
        if ("centralized".equals(type) || "central".equals(type)) return TaskV1Service.MODE_CENTRALIZED;
        if ("in-place".equals(type) || "affinity".equals(type)) return TaskV1Service.MODE_IN_PLACE;
        return type == null ? "UNKNOWN" : type.toUpperCase().replace('-', '_');
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 【架构修正#5】: 清理方法增加client参数，确保从正确的集群删除Job。
     */
    private void cleanupJob(KubernetesClient client, String jobName) {
        try {
            // 在新版本的fabric8客户端中, delete()返回List<StatusDetails>而非Boolean。
            // 调用成功且未抛出异常即表示删除请求已发出。
            client.batch().v1().jobs().inNamespace("default").withName(jobName).delete();
            log.info("已向集群 {} 发送Job '{}' 的删除请求。", getClusterIdFromClient(client), jobName);
        } catch (Exception e) {
            log.warn("从集群 {} 清理Job {} 时出错: {}", getClusterIdFromClient(client), jobName, e.getMessage());
        }
    }

    private void updateRegisteredTaskStatus(Integer taskId, String executionMode,
                                            List<String> schedules,
                                            List<DataItemResult> successful,
                                            int expectedCount) {
        Instant preparationStart = null;
        Instant preparationReady = null;
        Instant computeStart = null;
        Instant computeFinished = null;
        boolean completeEvidence = successful.size() == expectedCount;
        for (DataItemResult result : successful) {
            if (!hasCompleteEvidence(result)) {
                completeEvidence = false;
                continue;
            }
            if (preparationStart == null || result.getPreparationStartedAt().isBefore(preparationStart)) {
                preparationStart = result.getPreparationStartedAt();
            }
            if (preparationReady == null || result.getPreparationReadyAt().isAfter(preparationReady)) {
                preparationReady = result.getPreparationReadyAt();
            }
            if (computeStart == null || result.getComputeStartedAt().isBefore(computeStart)) {
                computeStart = result.getComputeStartedAt();
            }
            if (computeFinished == null || result.getComputeFinishedAt().isAfter(computeFinished)) {
                computeFinished = result.getComputeFinishedAt();
            }
        }
        Long preparationMs = preparationStart == null || preparationReady == null
                ? null : Math.max(0L, Duration.between(preparationStart, preparationReady).toMillis());
        Long computeMs = computeStart == null || computeFinished == null
                ? null : Math.max(0L, Duration.between(computeStart, computeFinished).toMillis());
        completeEvidence = completeEvidence && preparationMs != null && computeMs != null;

        TaskManagement summary = new TaskManagement();
        summary.setTaskId(taskId);
        summary.setSchedule(executionMode + "方案:" + String.join("\n", schedules));
        summary.setDataPreparationMs(preparationMs);
        summary.setComputeDurationMs(computeMs);
        summary.setExecutionEvidenceComplete(completeEvidence);
        summary.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (successful.isEmpty()) summary.setStatus("执行失败");
        else if (successful.size() < expectedCount || !completeEvidence) summary.setStatus("部分完成");
        else summary.setStatus("已完成");
        taskManagementMapper.updateExecutionSummary(summary);
        log.info("任务 {} 单模式执行结束: mode={}, status={}, dataPreparationMs={}, computeDurationMs={}",
                taskId, executionMode, summary.getStatus(), preparationMs, computeMs);
    }

    /**
     * 对比任务结果回写到同一行（沿用旧字段语义，单位为秒）：
     * T1 = Σ 各数据集分布式搬运耗时，T2 = Σ 各数据集集中式搬运耗时，rating = T2 / T1。
     * 只有 2N 个 Job 全部成功且证据完整时，两组求和才可比较；否则 T1/T2/rating 均置空，不做推算。
     *
     * @param inPlaceResults     与 datasetIds 一一对应，失败的 Job 为 null
     * @param centralizedResults 与 datasetIds 一一对应，失败的 Job 为 null
     */
    private void updateComparisonTaskStatus(Integer taskId, List<Long> datasetIds,
                                            List<DataItemResult> inPlaceResults,
                                            List<DataItemResult> centralizedResults) {
        int expectedCount = datasetIds.size() * 2;
        int successCount = 0;
        boolean completeEvidence = true;
        long inPlaceMs = 0L;
        long centralizedMs = 0L;
        List<String> inPlaceLines = new ArrayList<>();
        List<String> centralizedLines = new ArrayList<>();
        for (int i = 0; i < datasetIds.size(); i++) {
            String label = datasetLabel(datasetIds.get(i));
            DataItemResult inPlace = inPlaceResults.get(i);
            DataItemResult centralized = centralizedResults.get(i);
            inPlaceLines.add(comparisonScheduleLine(label, inPlace));
            centralizedLines.add(comparisonScheduleLine(label, centralized));
            for (DataItemResult result : Arrays.asList(inPlace, centralized)) {
                if (result == null) {
                    completeEvidence = false;
                    continue;
                }
                successCount++;
                if (!hasCompleteEvidence(result) || result.getPreparationMs() == null
                        || result.getPreparationMs() < 0) {
                    completeEvidence = false;
                }
            }
            if (inPlace != null && inPlace.getPreparationMs() != null) inPlaceMs += inPlace.getPreparationMs();
            if (centralized != null && centralized.getPreparationMs() != null) {
                centralizedMs += centralized.getPreparationMs();
            }
        }
        completeEvidence = completeEvidence && successCount == expectedCount;
        Double t1 = completeEvidence ? inPlaceMs / 1000.0 : null;
        Double t2 = completeEvidence ? centralizedMs / 1000.0 : null;
        Double rating = t1 != null && t1 > 0 ? t2 / t1 : null;

        List<String> scheduleLines = new ArrayList<>();
        scheduleLines.add("分布式调度方案:");
        scheduleLines.addAll(inPlaceLines);
        scheduleLines.add("中心化调度方案:");
        scheduleLines.addAll(centralizedLines);

        TaskManagement summary = new TaskManagement();
        summary.setTaskId(taskId);
        summary.setT1(t1);
        summary.setT2(t2);
        summary.setRating(rating);
        summary.setSchedule(String.join("\n", scheduleLines));
        summary.setExecutionEvidenceComplete(completeEvidence);
        summary.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (completeEvidence) summary.setStatus("已完成");
        else if (successCount == 0) summary.setStatus("执行失败");
        else summary.setStatus("部分完成");
        taskManagementMapper.updateTask(summary);
        log.info("对比任务 {} 执行结束: status={}, 成功 {}/{}, 分布式总搬运时间={}s, 集中式总搬运时间={}s, 加速比={}",
                taskId, summary.getStatus(), successCount, expectedCount,
                t1 == null ? "不可比较" : String.format("%.3f", t1),
                t2 == null ? "不可比较" : String.format("%.3f", t2),
                rating == null ? "不可计算" : String.format("%.3f", rating));
    }

    private String comparisonScheduleLine(String datasetLabel, DataItemResult result) {
        return result == null
                ? datasetLabel + ": 执行失败"
                : datasetLabel + ": " + result.getSourceNodeName() + " -> " + result.getTargetNodeName()
                        + (result.getPlacementNote() == null ? "" : "（" + result.getPlacementNote() + "）");
    }

    private boolean hasCompleteEvidence(DataItemResult result) {
        return result.getPreparationStartedAt() != null && result.getPreparationReadyAt() != null
                && result.getComputeStartedAt() != null && result.getComputeFinishedAt() != null
                && result.getActualNodeName() != null && result.getInputBytes() != null
                && result.getInputChecksumSha256() != null && result.getOutputChecksumSha256() != null;
    }

    private String normalizeExecutionMode(String mode) {
        if (TaskV1Service.MODE_CENTRALIZED.equalsIgnoreCase(mode)) return TaskV1Service.MODE_CENTRALIZED;
        if (TaskV1Service.MODE_IN_PLACE.equalsIgnoreCase(mode)) return TaskV1Service.MODE_IN_PLACE;
        if (TaskV1Service.MODE_COMPARISON.equalsIgnoreCase(mode)) return TaskV1Service.MODE_COMPARISON;
        throw new IllegalArgumentException("unsupported execution mode: " + mode);
    }

    /**
     * 外部（手动）调度方案只有一条执行路径：T1 为各 assignment 搬运耗时之和，T2/rating 保持为空，
     * 因而不会进入性能分析页。schedule 按调度目标节点分组展示。
     */
    private void updateFinalTaskStatus(Integer taskId, double totalT1, String finalSchedule,
                                       int successCount, int expectedCount) {
        TaskManagement finalTask = taskManagementMapper.getTaskByTaskId(taskId);
        if (finalTask != null) {
            finalTask.setT1(totalT1);
            finalTask.setT2(null);
            finalTask.setRating(null);
            finalTask.setSchedule(finalSchedule);
            if (successCount == 0) {
                finalTask.setStatus("执行失败");
            } else if (successCount < expectedCount) {
                finalTask.setStatus("部分完成");
            } else {
                finalTask.setStatus("已完成");
            }
            taskManagementMapper.updateTask(finalTask);

            log.info("==================== 任务 {} 完成 ====================", taskId);
            log.info("调度方案:\n{}", finalSchedule);
            log.info("外部调度方案总搬运时间: {}s, 成功 {}/{}",
                    String.format("%.3f", totalT1), successCount, expectedCount);
            log.info("===============================================================");
        }
    }
    private void updateTaskStatusToFailed(Integer taskId, String errorMessage) {
        TaskManagement failedTask = new TaskManagement();
        failedTask.setTaskId(taskId);
        failedTask.setStatus("执行失败");
        failedTask.setExecutionEvidenceComplete(false);
        failedTask.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        taskManagementMapper.updateExecutionSummary(failedTask);
    }

    private static final class InitContainerEvidence {
        private final long durationMs;
        private final Instant startedAt;
        private final Instant finishedAt;
        private final String podName;
        private final String nodeName;
        private final Long inputBytes;
        private final String inputChecksumSha256;

        private InitContainerEvidence(long durationMs, Instant startedAt, Instant finishedAt,
                                      String podName, String nodeName, Long inputBytes,
                                      String inputChecksumSha256) {
            this.durationMs = durationMs;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.podName = podName;
            this.nodeName = nodeName;
            this.inputBytes = inputBytes;
            this.inputChecksumSha256 = inputChecksumSha256;
        }
    }

    private static final class ProcessingEvidence {
        private final Instant startedAt;
        private final Instant finishedAt;
        private final String podName;
        private final String nodeName;
        private final String outputSummary;
        private final String outputChecksumSha256;

        private ProcessingEvidence(Instant startedAt, Instant finishedAt, String podName,
                                   String nodeName, String outputSummary,
                                   String outputChecksumSha256) {
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.podName = podName;
            this.nodeName = nodeName;
            this.outputSummary = outputSummary;
            this.outputChecksumSha256 = outputChecksumSha256;
        }
    }

    private static final class JobExecutionEvidence {
        private final Instant preparationStartedAt;
        private final Instant preparationReadyAt;
        private final Instant computeStartedAt;
        private final Instant computeFinishedAt;
        private final String nodeName;
        private final Long inputBytes;
        private final String inputChecksumSha256;
        private final String outputChecksumSha256;

        private JobExecutionEvidence(Instant preparationStartedAt, Instant preparationReadyAt,
                                     Instant computeStartedAt, Instant computeFinishedAt,
                                     String nodeName, Long inputBytes, String inputChecksumSha256,
                                     String outputChecksumSha256) {
            this.preparationStartedAt = preparationStartedAt;
            this.preparationReadyAt = preparationReadyAt;
            this.computeStartedAt = computeStartedAt;
            this.computeFinishedAt = computeFinishedAt;
            this.nodeName = nodeName;
            this.inputBytes = inputBytes;
            this.inputChecksumSha256 = inputChecksumSha256;
            this.outputChecksumSha256 = outputChecksumSha256;
        }
    }

    private String getClusterIdFromClient(KubernetesClient client) {
        try {
            Object ctxObj = client.getConfiguration().getCurrentContext();
            String ctx;
            if (ctxObj instanceof String) {
                ctx = (String) ctxObj;
            } else if (ctxObj instanceof NamedContext) {
                ctx = ((NamedContext) ctxObj).getName();
            } else {
                ctx = ctxObj != null ? ctxObj.toString() : null;
            }
            return ctx != null ? ctx : "unknown-context";
        } catch (Exception e) {
            log.warn("无法从 KubernetesClient 获取当前上下文: {}", e.getMessage());
            return "unknown-context";
        }
    }
}
