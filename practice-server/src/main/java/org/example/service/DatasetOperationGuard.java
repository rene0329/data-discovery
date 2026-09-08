package org.example.service;

import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Collection;
import java.util.TreeSet;

/** Admission locks are held until the task/plan reservation commits. Call in READ_COMMITTED transactions. */
public final class DatasetOperationGuard {
    private DatasetOperationGuard() { }

    public static void lock(DatasetRegistrationMapper mapper, Collection<Long> ids) {
        new TreeSet<>(ids).forEach(mapper::lockDataset);
    }

    public static boolean busy(DatasetRegistrationMapper mapper, RegisteredDataset dataset) {
        return mapper.countActiveSchedulingReferences(dataset.getDatasetId()) > 0
                || mapper.countActiveMigrationReferences(dataset.getDatasetId(), dataset.getLegacyDataId()) > 0
                || mapper.countActiveTaskReferences(dataset.getDatasetId(), dataset.getName()) > 0;
    }

    public static void requireIdle(DatasetRegistrationMapper mapper, RegisteredDataset dataset) {
        if (busy(mapper, dataset)) throw RegistrationException.conflict(
                "数据集 " + dataset.getName() + " 正在被任务或调度占用，请等待结束后重试");
    }

    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { action.run(); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
