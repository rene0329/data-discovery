package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.DurableTaskJob;
import org.example.entity.RegisteredDataset;
import org.example.entity.TaskManagement;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.DurableTaskJobMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.vo.DataItemResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.PreDestroy;

/** Finalizes external assignments only after their durable computations have stopped. */
@Service
@Slf4j
public class ExternalDurableJobHandler {
    private final SchedulingPlanMapper plans;
    private final TaskManagementMapper tasks;
    private final DatasetRegistrationMapper datasets;
    private final DurableTaskJobMapper jobs;
    private final ObjectMapper json;
    private final DatasetHeatService heat;
    private final ConcurrentMap<Long, Boolean> preparing = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor preparationWorkers = new ThreadPoolExecutor(0, 2, 60,
            TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "external-data-preparation");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ExternalDurableJobHandler(SchedulingPlanMapper plans, TaskManagementMapper tasks,
            DatasetRegistrationMapper datasets, DurableTaskJobMapper jobs, ObjectMapper json,
            DatasetHeatService heat) {
        this.plans = plans;
        this.tasks = tasks;
        this.datasets = datasets;
        this.jobs = jobs;
        this.json = json;
        this.heat = heat;
    }

    /** Slow storage copies must not block admission and observation of unrelated computations. */
    public void prepareAsync(DurableTaskJob row, Runnable action) {
        Long id = row.getId();
        if (preparing.putIfAbsent(id, Boolean.TRUE) != null) return;
        try {
            preparationWorkers.execute(() -> {
                try {
                    action.run();
                    jobs.markExternalPrepared(id);
                } catch (Exception ex) {
                    jobs.markPreparationFailure(id, "外部方案数据准备失败，请检查源副本及目标节点");
                    log.warn("外部方案数据准备失败 jobId={} exceptionType={}", id, ex.getClass().getSimpleName());
                } finally {
                    preparing.remove(id);
                }
            });
        } catch (RejectedExecutionException ex) {
            preparing.remove(id);
            // No side effects happened. The persisted job remains eligible for the next pass.
        }
    }

    public boolean isPreparing(Long id) { return preparing.containsKey(id); }

    @PreDestroy
    public void stopPreparationWorkers() { preparationWorkers.shutdown(); }

    @Transactional
    public void finalizePlan(Integer taskId, List<DurableTaskJob> rows) {
        if (rows.isEmpty() || rows.stream().anyMatch(row -> !row.terminal())) return;
        TaskManagement task = tasks.getTaskByTaskId(taskId);
        if (task == null || terminal(task.getStatus())) return;
        int completed = 0;
        int cancelled = 0;
        double seconds = 0;
        String error = null;
        Set<Long> readDatasets = new LinkedHashSet<>();
        Set<Long> planIds = new LinkedHashSet<>();
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (DurableTaskJob row : rows) {
            if (row.getPlanId() != null) planIds.add(row.getPlanId());
            String state = row.getState();
            DataItemResult result = null;
            if ("COMPLETED".equals(state)) {
                try {
                    result = json.readValue(row.getResultJson(), DataItemResult.class);
                } catch (Exception ex) {
                    throw new IllegalStateException("无法读取外部作业执行结果: " + row.getId(), ex);
                }
                completed++;
                seconds += result.getT1Seconds();
                readDatasets.add(row.getDatasetId());
            } else if ("CANCELLED".equals(state)) {
                cancelled++;
            } else {
                error = row.getWaitingReason();
            }
            if (row.getAssignmentId() != null) {
                plans.updateAssignmentStatus(row.getAssignmentId(), state,
                        "COMPLETED".equals(state) ? null : row.getWaitingReason());
            }
            List<String> targetLines = lines.computeIfAbsent(nodeLabel(row.getTargetNode()), key -> new ArrayList<>());
            targetLines.add(result != null ? result.getScheduleT1()
                    : datasetLabel(row.getDatasetId()) + ("CANCELLED".equals(state) ? ": 已取消" : ": 执行失败"));
        }
        String planStatus = completed == rows.size() ? "COMPLETED"
                : cancelled == rows.size() ? "CANCELLED" : completed > 0 ? "PARTIAL_COMPLETED" : "FAILED";
        for (Long planId : planIds) plans.updatePlanStatus(planId, planStatus, error);
        List<String> schedule = new ArrayList<>();
        lines.forEach((node, values) -> {
            schedule.add("调度目标节点 " + node + ":");
            schedule.addAll(values);
        });
        task.setStatus(completed == rows.size() ? "已完成" : cancelled == rows.size() ? "已取消"
                : completed > 0 ? "部分完成" : "执行失败");
        task.setT1(seconds);
        task.setT2(null);
        task.setRating(null);
        task.setSchedule(String.join("\n", schedule));
        task.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        tasks.updateTask(task);
        tasks.updateExecutionSummary(task);
        for (Long datasetId : readDatasets) {
            DatasetHeatService.logAccessUpdate(datasetId, taskId, null, heat.recordAccess(datasetId));
        }
    }

    private static boolean terminal(String state) {
        return "已完成".equals(state) || "部分完成".equals(state) || "执行失败".equals(state)
                || "已取消".equals(state);
    }

    private String nodeLabel(String name) {
        if (name == null || name.trim().isEmpty()) return "未分配";
        return name;
    }

    private String datasetLabel(Long id) {
        RegisteredDataset dataset = datasets.findDatasetById(id);
        return dataset != null && dataset.getDatasetCode() != null ? dataset.getDatasetCode() : "数据集#" + id;
    }
}
