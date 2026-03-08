package org.example.service;

import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
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
    private final Executor dataProcessingExecutor;
    // 【架构修正#1】: 不再需要单例的KubernetesClient，已移除。

    /** 当亲和性调度的目标节点就是数据所在源节点时，跳过实际 Job 直接返回此基础时间(ms)，当文件大小为0时兜底使用。*/
    @Value("${dispatch.scheduler.in-place-baseline-ms:50}")
    private long inPlaceBaselineMs;

    /** 原地调度场景下模拟本地磁盘读取速率，用于按文件大小估算原地传输时间，使加速比更合理。*/
    @Value("${dispatch.scheduler.in-place-rate:100m}")
    private String inPlaceRate;

    @Autowired
    public K8sTaskOrchestratorService(
            DataManagementMapper dataManagementMapper,
            NodeManagementMapper nodeManagementMapper,
            TaskManagementMapper taskManagementMapper,
            MigrationTaskMapper migrationTaskMapper,
            // 【架构修正#2】: 从构造函数中移除KubernetesClient
            K8sJobFactory k8sJobFactory,
            @Value("${dispatch.central-node.name:aks-nodepool1-54677688-vmss000002}") String centralNodeName,
            @Qualifier("dataProcessingExecutor") Executor dataProcessingExecutor
    ) {
        this.dataManagementMapper = dataManagementMapper;
        this.nodeManagementMapper = nodeManagementMapper;
        this.taskManagementMapper = taskManagementMapper;
        this.migrationTaskMapper = migrationTaskMapper;
        this.k8sJobFactory = k8sJobFactory;
        this.centralNodeName = centralNodeName;
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

            updateFinalTaskStatus(taskId, totalT1, totalT2, scheduleT1List, scheduleT2List);

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

        AtomicReference<String> affinityNodeOut = new AtomicReference<>(sourceNodeName);
        AtomicReference<String> centralNodeOut  = new AtomicReference<>(centralNodeName);
        long t1_ms = executeJobAndMeasureInitContainer(taskId, "affinity", sourceNodeInfo, null, dataInfo, affinityNodeOut);
        long t2_ms = executeJobAndMeasureInitContainer(taskId, "central", sourceNodeInfo, centralNodeName, dataInfo, centralNodeOut);

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
     * 【架构修正#3】: 核心方法重构。现在使用从Factory获取的、与Job匹配的正确客户端。
     */
    private long executeJobAndMeasureInitContainer(Integer taskId,
                                                   String type,
                                                   NodeManagement sourceNodeInfo,
                                                   String targetNode,
                                                   DataManagement dataInfo,
                                                   AtomicReference<String> selectedNodeOut) {

        String jobName = String.format("%s-%s-%s", type, dataInfo.getDataName().toLowerCase().replace("_", "-"), UUID.randomUUID().toString().substring(0, 8));
        log.info("准备Job: {} (源: {}, 目标: {})", jobName, sourceNodeInfo.getNodeName(), targetNode);

        KubernetesClient client = null;
        MigrationTask migrationTask = null;
        try {
            JobCreationResult jobResult = k8sJobFactory.createDataProcessingJob(jobName, sourceNodeInfo.getNodeName(), dataInfo.getDataName(), dataInfo.getFilePath(), targetNode, dataInfo.getRequiredCpu(), dataInfo.getRequiredMemory());

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

            // 记录迁移任务：PLANNED -> COPYING -> VERIFYING -> SWITCHING -> COMPLETED/FAILED
            migrationTask = MigrationTask.builder()
                    .taskId(taskId)
                    .dataId(dataInfo.getDataId())
                    .sourceNodeId(sourceNodeInfo.getNodeId())
                    .targetNodeId(targetNodeInfo != null ? targetNodeInfo.getNodeId() : sourceNodeInfo.getNodeId())
                    .status("PLANNED")
                    .retryCount(0)
                    .build();
            migrationTaskMapper.insert(migrationTask);

            migrationTask.setStatus("COPYING");
            migrationTask.setStartedAt(LocalDateTime.now());
            migrationTaskMapper.updateLifecycle(migrationTask);

            client.batch().v1().jobs().inNamespace("default").create(job);

            client.batch().v1().jobs().inNamespace("default").withName(jobName)
                    .waitUntilCondition(j -> j != null && j.getStatus() != null &&
                                    (j.getStatus().getSucceeded() != null || j.getStatus().getFailed() != null),
                            10, TimeUnit.MINUTES);

            Job finalJob = client.batch().v1().jobs().inNamespace("default").withName(jobName).get();
            if (finalJob == null || finalJob.getStatus().getSucceeded() == null || finalJob.getStatus().getSucceeded() < 1) {
                log.error("Job {} 在集群 {} 中执行失败或超时", jobName, getClusterIdFromClient(client));
                if (migrationTask != null) {
                    migrationTask.setStatus("FAILED");
                    migrationTask.setErrorMessage("k8s job failed or timeout");
                    migrationTask.setFinishedAt(LocalDateTime.now());
                    migrationTaskMapper.updateLifecycle(migrationTask);
                }
                return -1;
            }
            log.info("Job {} 在集群 {} 中执行成功，开始提取Init Container执行时间", jobName, getClusterIdFromClient(client));

            migrationTask.setStatus("VERIFYING");
            migrationTaskMapper.updateLifecycle(migrationTask);

            migrationTask.setStatus("SWITCHING");
            migrationTaskMapper.updateLifecycle(migrationTask);

            // data_server 不在此处更新：亲和性调度只是临时将数据下载到 emptyDir 进行训练，
            // 并未持久化到目标节点，data_server 应始终反映数据文件真实所在的节点。

            migrationTask.setStatus("COMPLETED");
            migrationTask.setFinishedAt(LocalDateTime.now());
            migrationTaskMapper.updateLifecycle(migrationTask);

            if (selectedNodeOut != null) selectedNodeOut.set(selectedTargetNodeName);
            return getInitContainerDuration(client, jobName, "data-transfer-container");

        } catch (Exception e) {
            log.error("执行Job {} 或测量时间时出现异常", jobName, e);
            if (migrationTask != null) {
                migrationTask.setStatus("FAILED");
                migrationTask.setErrorMessage(e.getMessage());
                migrationTask.setFinishedAt(LocalDateTime.now());
                migrationTaskMapper.updateLifecycle(migrationTask);
            }
            return -1;
        } finally {
            if (client != null) {
                cleanupJob(client, jobName);
            }
        }
    }

    /**
     * 【架构修正#4】: 方法增加一个client参数，以确保从正确的集群获取Pod信息。
     */
    private long getInitContainerDuration(KubernetesClient client, String jobName, String initContainerName) {
        try {
            List<Pod> pods = client.pods().inNamespace("default").withLabel("job-name", jobName).list().getItems();
            if (pods.isEmpty()) {
                log.error("在集群 {} 中找不到Job {} 关联的Pod", getClusterIdFromClient(client), jobName);
                return -1;
            }
            Pod pod = pods.get(0);
            String podName = pod.getMetadata().getName();

            // 从 init container 日志中解析 TRANSFER_MS=<ms>（毫秒精度，由 init container 命令输出）
            try {
                String logs = client.pods().inNamespace("default").withName(podName)
                        .inContainer(initContainerName).getLog();
                if (logs != null) {
                    for (String line : logs.split("\n")) {
                        if (line.startsWith("TRANSFER_MS=")) {
                            long duration = Long.parseLong(line.substring("TRANSFER_MS=".length()).trim());
                            log.info("精确测量到 Init Container '{}' 的执行时间为: {} ms", initContainerName, duration);
                            return duration;
                        }
                    }
                }
            } catch (Exception logEx) {
                log.warn("读取 Init Container '{}' 日志失败，回退到 K8s 时间戳: {}", initContainerName, logEx.getMessage());
            }

            // 回退：使用 K8s 时间戳（秒级精度）
            if (pod.getStatus() != null && pod.getStatus().getInitContainerStatuses() != null) {
                for (ContainerStatus status : pod.getStatus().getInitContainerStatuses()) {
                    if (status.getName().equals(initContainerName)) {
                        ContainerStateTerminated terminatedState = status.getState().getTerminated();
                        if (terminatedState != null && terminatedState.getFinishedAt() != null && terminatedState.getStartedAt() != null) {
                            Instant startTime = Instant.parse(terminatedState.getStartedAt());
                            Instant finishTime = Instant.parse(terminatedState.getFinishedAt());
                            long duration = Duration.between(startTime, finishTime).toMillis();
                            log.info("回退测量到 Init Container '{}' 的执行时间为: {} ms (K8s 时间戳，秒级精度)", initContainerName, duration);
                            return duration;
                        }
                    }
                }
            }
            log.error("在Pod {} 中找不到名为 '{}' 的 Init Container 的有效时间信息", podName, initContainerName);
            return -1;
        } catch (Exception e) {
            log.error("提取Job {} 的Init Container时长时出错", jobName, e);
            return -1;
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

    // ... updateFinalTaskStatus 和 updateTaskStatusToFailed 方法无需修改 ...
    private void updateFinalTaskStatus(Integer taskId, double totalT1, double totalT2,
                                       List<String> scheduleT1List, List<String> scheduleT2List) {
        TaskManagement finalTask = taskManagementMapper.getTaskByTaskId(taskId);
        if (finalTask != null) {
            double rating = totalT1 > 0 ? (totalT2 / totalT1) : 0;
            String finalSchedule = "分布式调度方案:" + String.join("\n", scheduleT1List) +
                    "\n中心化调度方案:" + String.join("\n", scheduleT2List);

            finalTask.setT1(totalT1);
            finalTask.setT2(totalT2);
            finalTask.setRating(rating);
            finalTask.setSchedule(finalSchedule);
            finalTask.setStatus("已完成");
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
        failedTask.setStatus("执行失败: " + errorMessage);
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