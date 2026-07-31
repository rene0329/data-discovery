package org.example.service;

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
import org.example.entity.TaskManagement;
import org.example.factory.JobCreationResult;
import org.example.factory.K8sJobFactory;
import org.example.mapper.DataManagementMapper;
import org.example.mapper.MigrationTaskMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.vo.DataItemResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private final String centralNodeName;
    private final String centralNodeIp;
    private final Executor dataProcessingExecutor;
    // 【架构修正#1】: 不再需要单例的KubernetesClient，已移除。

    /** 当亲和性调度的目标节点就是数据所在源节点时，跳过实际 Job 直接返回此基础时间(ms)，当文件大小为0时兜底使用。*/
    @Value("${dispatch.scheduler.in-place-baseline-ms:50}")
    private long inPlaceBaselineMs;

    /** 原地调度场景下模拟本地磁盘读取速率，用于按文件大小估算原地传输时间，使加速比更合理。*/
    @Value("${dispatch.scheduler.in-place-rate:100m}")
    private String inPlaceRate;

    /** 等待单个 K8s Job 完成的超时时间（分钟）。 */
    @Value("${app.orchestrator.job-wait-timeout-minutes:30}")
    private long jobWaitTimeoutMinutes;

    /** Job 失败后的最大重试次数（不含首次执行，1 表示总共最多执行 2 次）。 */
    @Value("${app.orchestrator.job-max-retries:1}")
    private int jobMaxRetries;

    /** 重试前的退避时间（秒）。 */
    @Value("${app.orchestrator.job-retry-backoff-seconds:5}")
    private long jobRetryBackoffSeconds;

    /**
     * 传输计时与训练完成解耦。默认在 init container 成功后立即返回测量值，
     * 训练 Job 继续运行并由 ttlSecondsAfterFinished 自动清理。
     */
    @Value("${app.orchestrator.wait-for-processing-completion:false}")
    private boolean waitForProcessingCompletion;

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
            @Value("${dispatch.central-node.name:}") String centralNodeName,
            @Value("${dispatch.central-node.ip:}") String centralNodeIp,
            @Qualifier("dataProcessingExecutor") Executor dataProcessingExecutor
    ) {
        this.dataManagementMapper = dataManagementMapper;
        this.nodeManagementMapper = nodeManagementMapper;
        this.taskManagementMapper = taskManagementMapper;
        this.migrationTaskMapper = migrationTaskMapper;
        this.k8sJobFactory = k8sJobFactory;
        this.centralNodeName = centralNodeName;
        this.centralNodeIp = centralNodeIp;
        this.dataProcessingExecutor = dataProcessingExecutor;
    }


    @Async
    public void executeTask(Integer taskId, List<String> selectedDatas) {
        log.info("任务 {} 开始执行，使用K8s原生调度器+亲和性策略处理 {} 个数据项", taskId, selectedDatas.size());

        try {
            List<CompletableFuture<DataItemResult>> futures = selectedDatas.stream()
                    .map(dataItem -> CompletableFuture
                            .supplyAsync(() -> processDataItem(taskId, dataItem), dataProcessingExecutor)
                            .exceptionally(ex -> {
                                // 单个子任务失败只记录日志，不影响其他子任务
                                log.error("数据项 '{}' 处理失败，已跳过: {}", dataItem, ex.getMessage());
                                return null;
                            }))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("任务 {} 的所有Job执行完毕，开始汇总结果...", taskId);

            List<String> scheduleT1List = new ArrayList<>();
            List<String> scheduleT2List = new ArrayList<>();
            double totalT1 = 0.0;
            double totalT2 = 0.0;

            for (CompletableFuture<DataItemResult> future : futures) {
                DataItemResult result = future.get();
                if (result != null) {
                    scheduleT1List.add(result.getScheduleT1());
                    scheduleT2List.add(result.getScheduleT2());
                    totalT1 += result.getT1Seconds();
                    totalT2 += result.getT2Seconds();
                }
            }

            updateFinalTaskStatus(taskId, totalT1, totalT2, scheduleT1List, scheduleT2List,
                    scheduleT1List.size(), selectedDatas.size());

        } catch (Exception e) {
            log.error("任务 {} 执行过程中发生错误", taskId, e);
            updateTaskStatusToFailed(taskId, e.getMessage());
        }
    }


    private DataItemResult processDataItem(Integer taskId, String dataItem) {
        log.info("开始处理数据项: {}", dataItem);
        DataManagement dataInfo = dataManagementMapper.findDataByName(dataItem);
        if (dataInfo == null) {
            log.error("找不到数据项 '{}' 的信息", dataItem);
            return null;
        }
        String sourceNodeName = dataInfo.getDataServer();
        NodeManagement sourceNodeInfo = nodeManagementMapper.getNodeByName(sourceNodeName);
        if (sourceNodeInfo == null) {
            log.error("在数据库中找不到源节点 '{}' 的集群信息", sourceNodeName);
            return null;
        }

        String resolvedCentralNodeName = resolveCentralNodeName();
        AtomicReference<String> affinityNodeOut = new AtomicReference<>(sourceNodeName);
        AtomicReference<String> centralNodeOut  = new AtomicReference<>(resolvedCentralNodeName);
        long t1_ms = executeJobAndMeasureInitContainer(taskId, "affinity", sourceNodeInfo, null, dataInfo, affinityNodeOut);
        long t2_ms = executeJobAndMeasureInitContainer(taskId, "central", sourceNodeInfo, resolvedCentralNodeName, dataInfo, centralNodeOut);

        if (t1_ms == -1 || t2_ms == -1) {
            log.error("数据项 {} 的Job执行失败", dataItem);
            return null;
        }

        String affinityTarget = affinityNodeOut.get();
        boolean inPlace = sourceNodeName.equals(affinityTarget);
        DataItemResult result = new DataItemResult();
        result.setT1Seconds(t1_ms / 1000.0);
        result.setT2Seconds(t2_ms / 1000.0);
        result.setScheduleT1(dataItem + ": " + sourceNodeName + " -> " + affinityTarget + (inPlace ? " (原地)" : ""));
        result.setScheduleT2(dataItem + ": " + sourceNodeName + " -> " + centralNodeOut.get());
        log.info("数据项 {} 处理完成。亲和性调度传输: {}ms, 中心化调度传输: {}ms", dataItem, t1_ms, t2_ms);
        return result;
    }

    /**
     * 优先按中心节点 InternalIP/ExternalIP 解析真实 K8s 节点名，避免部署时猜测节点名。
     * 若未配置 IP，则兼容使用显式配置的节点名。
     */
    private String resolveCentralNodeName() {
        if (centralNodeIp != null && !centralNodeIp.trim().isEmpty()) {
            Integer nodeId = nodeManagementMapper.getNodeIdByIp(centralNodeIp.trim());
            if (nodeId != null) {
                NodeManagement node = nodeManagementMapper.getNodeById(nodeId);
                if (node != null && node.getNodeName() != null && !node.getNodeName().trim().isEmpty()) {
                    return node.getNodeName();
                }
            }
            throw new IllegalStateException("找不到中心节点 IP " + centralNodeIp + " 对应的 node_management 记录");
        }
        if (centralNodeName != null && !centralNodeName.trim().isEmpty()) {
            return centralNodeName.trim();
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
                                                   DataManagement dataInfo,
                                                   AtomicReference<String> selectedNodeOut) {
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
            try {
                log.info("准备Job: {} (源: {}, 目标: {}, attempt={}/{})",
                        jobName, sourceNodeInfo.getNodeName(), targetNode, attempt + 1, totalAttempts);

                JobCreationResult jobResult = k8sJobFactory.createDataProcessingJob(
                        jobName,
                        sourceNodeInfo.getNodeName(),
                        dataInfo.getDataName(),
                        dataInfo.getFilePath(),
                        targetNode,
                        dataInfo.getRequiredCpu(),
                        dataInfo.getRequiredMemory());

                client = jobResult.getClient();
                Job job = jobResult.getJob();
                String selectedTargetNodeName = jobResult.getSelectedNodeName();

                // 【原地检测】亲和性调度结果为数据所在源节点本身 → 数据已在最优节点，无需迁移
                // 直接返回基础时间，避免发起无意义的 K8s Job 并防止后续速率计算除零
                if ("affinity".equals(type) && sourceNodeInfo.getNodeName().equals(selectedTargetNodeName)) {
                    long fileSizeBytes = dataInfo.getDataSize() != null ? dataInfo.getDataSize() : 0L;
                    long baseline = k8sJobFactory.calculateBaselineMsWithRate(fileSizeBytes, inPlaceRate);
                    if (baseline <= 0) baseline = inPlaceBaselineMs;
                    log.info("数据项[{}] 亲和性调度目标 = 源节点 {}（原地），跳过 K8s Job，本地读取速率 {} 估算时间 {}ms",
                            dataInfo.getDataName(), selectedTargetNodeName, inPlaceRate, baseline);
                    if (selectedNodeOut != null) selectedNodeOut.set(selectedTargetNodeName);
                    return baseline;
                }

                NodeManagement targetNodeInfo = nodeManagementMapper.getNodeByName(selectedTargetNodeName);

                if (migrationTask == null) {
                    // 记录迁移任务：PLANNED -> COPYING -> VERIFYING -> SWITCHING -> COMPLETED/FAILED
                    migrationTask = MigrationTask.builder()
                            .taskId(taskId)
                            .dataId(dataInfo.getDataId())
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

                // 只等待数据传输 init container。这样训练镜像拉取、训练脚本失败或训练超时，
                // 都不会抹掉已经成功取得的传输时间。
                long transferDurationMs = waitForInitContainerDuration(
                        client, jobName, "data-transfer-container", waitTimeoutMinutes);
                log.info("Job {} 在集群 {} 中已取得传输时间 {}ms",
                        jobName, getClusterIdFromClient(client), transferDurationMs);

                migrationTask.setStatus("VERIFYING");
                migrationTaskMapper.updateLifecycle(migrationTask);

                if (waitForProcessingCompletion) {
                    waitForJobCompletion(client, jobName, waitTimeoutMinutes);
                } else {
                    // Job 仍可能在执行训练，不能在 finally 中立即删除；由 Job TTL 自动清理。
                    cleanupOnExit = false;
                }

                // data_server 不在此处更新：亲和性调度只是临时将数据下载到 emptyDir 进行训练，
                // 并未持久化到目标节点，data_server 应始终反映数据文件真实所在的节点。

                migrationTask.setStatus("COMPLETED");
                migrationTask.setRetryCount(attempt);
                migrationTask.setFinishedAt(LocalDateTime.now());
                migrationTaskMapper.updateLifecycle(migrationTask);

                if (selectedNodeOut != null) selectedNodeOut.set(selectedTargetNodeName);
                return transferDurationMs;

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
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
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

                log.error("执行Job {}（集群 {}）已达到最大重试次数，最终失败", jobName, clusterId, e);
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

    /**
     * 【架构修正#4】: 方法增加一个client参数，以确保从正确的集群获取Pod信息。
     */
    private long waitForInitContainerDuration(KubernetesClient client,
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
                if (terminated == null || terminated.getExitCode() == null || terminated.getExitCode() != 0) {
                    continue;
                }
                Long duration = extractInitContainerDuration(client, pod, initContainerName, terminated);
                if (duration != null && duration >= 0) {
                    return duration;
                }
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

    private void waitForJobCompletion(KubernetesClient client,
                                      String jobName,
                                      long timeoutMinutes) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes);
        long pollMs = Math.max(200L, statusPollIntervalMs);
        while (System.nanoTime() < deadlineNanos) {
            Job job = client.batch().v1().jobs().inNamespace("default").withName(jobName).get();
            if (hasJobCondition(job, "Complete")) {
                return;
            }
            if (hasJobCondition(job, "Failed")) {
                throw new IllegalStateException("Job " + jobName + " 的处理容器执行失败");
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

    private Long extractInitContainerDuration(KubernetesClient client,
                                              Pod pod,
                                              String initContainerName,
                                              ContainerStateTerminated terminatedState) {
        String podName = pod.getMetadata().getName();
        try {
            String logs = client.pods().inNamespace("default").withName(podName)
                    .inContainer(initContainerName).getLog();
            if (logs != null) {
                for (String line : logs.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("TRANSFER_MS=")) {
                        long duration = Long.parseLong(trimmed.substring("TRANSFER_MS=".length()).trim());
                        log.info("精确测量到 Init Container '{}' 的执行时间为: {} ms", initContainerName, duration);
                        return duration;
                    }
                }
            }
        } catch (Exception logEx) {
            log.warn("读取 Init Container '{}' 日志失败，回退到 K8s 时间戳: {}",
                    initContainerName, logEx.getMessage());
        }

        if (terminatedState.getFinishedAt() != null && terminatedState.getStartedAt() != null) {
            Instant startTime = Instant.parse(terminatedState.getStartedAt());
            Instant finishTime = Instant.parse(terminatedState.getFinishedAt());
            long duration = Duration.between(startTime, finishTime).toMillis();
            log.info("回退测量到 Init Container '{}' 的执行时间为: {} ms (K8s 时间戳)",
                    initContainerName, duration);
            return duration;
        }
        return null;
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

    // ... updateFinalTaskStatus 和 updateTaskStatusToFailed 方法无需修改 ...
    private void updateFinalTaskStatus(Integer taskId, double totalT1, double totalT2,
                                       List<String> scheduleT1List, List<String> scheduleT2List,
                                       int successCount, int expectedCount) {
        TaskManagement finalTask = taskManagementMapper.getTaskByTaskId(taskId);
        if (finalTask != null) {
            double rating = totalT1 > 0 ? (totalT2 / totalT1) : 0;
            String finalSchedule = "分布式调度方案:" + String.join("\n", scheduleT1List) +
                    "\n中心化调度方案:" + String.join("\n", scheduleT2List);

            finalTask.setT1(totalT1);
            finalTask.setT2(totalT2);
            finalTask.setRating(rating);
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
            log.info("亲和性调度总时间: {}s, 中心化调度总时间: {}s, 性能比: {}",
                    String.format("%.3f", totalT1), String.format("%.3f", totalT2), String.format("%.3f", rating));
            log.info("===============================================================");
        }
    }
    private void updateTaskStatusToFailed(Integer taskId, String errorMessage) {
        TaskManagement failedTask = new TaskManagement();
        failedTask.setTaskId(taskId);
        failedTask.setStatus("执行失败");
        taskManagementMapper.updateTask(failedTask);
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
