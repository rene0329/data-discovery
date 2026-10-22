package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.SchedulingAssignment;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Elapsed-time activity indicator; no byte-level progress is available from the agent. */
@Slf4j
final class StorageTransferProgress implements AutoCloseable {
    private final SchedulingAssignment assignment;
    private final long started = System.nanoTime();
    private final ScheduledExecutorService scheduler;
    private boolean closed;
    private int frame;

    StorageTransferProgress(SchedulingAssignment assignment) {
        this.assignment = assignment;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "storage-transfer-progress");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
    }

    synchronized void tick() {
        if (closed) return;
        StringBuilder bar = new StringBuilder("..........");
        bar.setCharAt(frame++ % 10, '=');
        log.info("【数据迁移】迁移中 [{}] planId={} assignmentId={} datasetId={} 已等待={}秒",
                bar, assignment.getPlanId(), assignment.getAssignmentId(), assignment.getDatasetId(),
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started));
    }

    @Override
    public synchronized void close() {
        closed = true;
        scheduler.shutdownNow();
    }

    static String secondsSince(long start) {
        return java.math.BigDecimal.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), 3)
                .stripTrailingZeros().toPlainString();
    }
}
