package org.example.service;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.entity.TaskExecutionEvent;
import org.example.entity.TaskManagement;
import org.example.factory.K8sJobFactory;
import org.example.mapper.TaskManagementMapper;
import org.example.mapper.TaskRecoveryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;


/**
 * The orchestrator runs in memory and cannot resume after a JVM restart.
 * Recreate deployment and a single replica ensure the previous executor has stopped.
 * Recover before HTTP startup, and stop orphaned jobs before releasing dataset reservations.
 */
@Service
public class InterruptedTaskRecovery implements SmartInitializingSingleton {
    public static final String TASK_LABEL = "topic4.io/task-id";
    static final String REASON = "后端服务重启，原任务执行已中断，请重新提交任务。";
    private static final Logger log = LoggerFactory.getLogger(InterruptedTaskRecovery.class);
    private final TaskRecoveryMapper recovery;
    private final TaskManagementMapper tasks;
    private final K8sJobFactory jobs;
    private final TransactionTemplate transaction;

    public InterruptedTaskRecovery(TaskRecoveryMapper recovery, TaskManagementMapper tasks,
                                   K8sJobFactory jobs, PlatformTransactionManager transactions) {
        this.recovery = recovery;
        this.tasks = tasks;
        this.jobs = jobs;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<TaskManagement> interrupted = recovery.listInterruptedTasks();
        for (TaskManagement task : interrupted) {
            stopOrphanedJobs(task.getTaskId());
            transaction.execute(status -> {
                if (recovery.failTask(task.getTaskId()) == 0) return null;
                recovery.failMigrations(task.getTaskId(), REASON);
                recovery.failAssignments(task.getTaskId(), REASON);
                recovery.failPlans(task.getTaskId(), REASON);
                TaskExecutionEvent event = new TaskExecutionEvent();
                event.setTaskId(task.getTaskId());
                event.setExecutionMode(task.getExecutionMode());
                event.setEventType("TASK_INTERRUPTED");
                event.setOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
                event.setDetailsJson("{\"reason\":\"SERVER_RESTART\",\"message\":\"" + REASON + "\"}");
                tasks.insertExecutionEvent(event);
                return null;
            });
            log.warn("Recovered interrupted task {} after server restart", task.getTaskId());
        }
    }

    private void stopOrphanedJobs(Integer taskId) {
        if (jobs.getClusterClients().isEmpty()) {
            throw new IllegalStateException("Cannot recover interrupted tasks without Kubernetes access");
        }
        for (KubernetesClient client : jobs.getClusterClients().values()) {
            // Only jobs carrying our exact task identity are touched, including remote clusters.
            client.batch().v1().jobs().inNamespace("default").withLabel(TASK_LABEL, taskId.toString())
                    .withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
            // Foreground deletion must finish before the transaction releases the reservation.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            while (true) {
                boolean noJobs = client.batch().v1().jobs().inNamespace("default")
                        .withLabel(TASK_LABEL, taskId.toString()).list().getItems().isEmpty();
                boolean noPods = client.pods().inNamespace("default")
                        .withLabel(TASK_LABEL, taskId.toString()).list().getItems().isEmpty();
                if (noJobs && noPods) break;
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Timed out stopping interrupted task " + taskId);
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while stopping task " + taskId, ex);
                }
            }
        }
    }
}
