package org.example.service;

import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.GracePeriodConfigurable;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.entity.TaskManagement;
import org.example.entity.TaskExecutionEvent;
import org.example.factory.K8sJobFactory;
import org.example.mapper.TaskManagementMapper;
import org.example.mapper.TaskRecoveryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class InterruptedTaskRecoveryTest {
    private final TaskRecoveryMapper mapper = mock(TaskRecoveryMapper.class);
    private final TaskManagementMapper tasks = mock(TaskManagementMapper.class);
    private final K8sJobFactory factory = mock(K8sJobFactory.class);
    private final KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
    private final FilterWatchListDeletable<Job, JobList, Resource<Job>> jobFilter = mock(FilterWatchListDeletable.class);
    private final FilterWatchListDeletable<Pod, PodList, PodResource> podFilter = mock(FilterWatchListDeletable.class);
    private final GracePeriodConfigurable<?> deleter = mock(GracePeriodConfigurable.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final InterruptedTaskRecovery recovery = new InterruptedTaskRecovery(mapper, tasks, factory, transactions);

    @BeforeEach
    void setup() {
        TaskManagement task = new TaskManagement();
        task.setTaskId(243);
        task.setExecutionMode("COMPARISON");
        when(mapper.listInterruptedTasks()).thenReturn(Collections.singletonList(task));
        when(mapper.failTask(243)).thenReturn(1);
        when(factory.getClusterClients()).thenReturn(Collections.singletonMap("test", client));
        NonNamespaceOperation jobOperations = mock(NonNamespaceOperation.class);
        NonNamespaceOperation podOperations = mock(NonNamespaceOperation.class);
        MixedOperation jobMixed = client.batch().v1().jobs();
        MixedOperation podMixed = client.pods();
        doReturn(jobOperations).when(jobMixed).inNamespace("default");
        doReturn(podOperations).when(podMixed).inNamespace("default");
        doReturn(jobFilter).when(jobOperations)
                .withLabel(InterruptedTaskRecovery.TASK_LABEL, "243");
        doReturn(podFilter).when(podOperations)
                .withLabel(InterruptedTaskRecovery.TASK_LABEL, "243");
        when(jobFilter.list()).thenReturn(new JobList());
        when(podFilter.list()).thenReturn(new PodList());
        doReturn(deleter).when(jobFilter).withPropagationPolicy(DeletionPropagation.FOREGROUND);
        when(transactions.getTransaction(any())).thenReturn(status);
    }

    @Test
    void stopsOrphansBeforeAtomicallyReleasingTaskAndAllLinkedReservations() {
        recovery.afterSingletonsInstantiated();
        InOrder order = inOrder(deleter, mapper, tasks, transactions);
        order.verify(deleter).delete();
        order.verify(transactions).getTransaction(any());
        order.verify(mapper).failTask(243);
        order.verify(mapper).failMigrations(243, InterruptedTaskRecovery.REASON);
        order.verify(mapper).failAssignments(243, InterruptedTaskRecovery.REASON);
        order.verify(mapper).failPlans(243, InterruptedTaskRecovery.REASON);
        ArgumentCaptor<TaskExecutionEvent> event = ArgumentCaptor.forClass(TaskExecutionEvent.class);
        order.verify(tasks).insertExecutionEvent(event.capture());
        order.verify(transactions).commit(status);
        assertEquals("TASK_INTERRUPTED", event.getValue().getEventType());
        assertTrue(event.getValue().getDetailsJson().contains("SERVER_RESTART"));
    }

    @Test
    void leavesReservationsIntactAndFailsStartupWhenKubernetesCannotBeChecked() {
        when(jobFilter.list()).thenThrow(new IllegalStateException("Kubernetes unavailable"));
        assertThrows(IllegalStateException.class, recovery::afterSingletonsInstantiated);
        verify(mapper, never()).failTask(anyInt());
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    void rollsBackIfAnyLinkedRecordCannotBeReconciled() {
        when(mapper.failMigrations(anyInt(), anyString())).thenThrow(new IllegalStateException("database failure"));
        assertThrows(IllegalStateException.class, recovery::afterSingletonsInstantiated);
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
        verify(tasks, never()).insertExecutionEvent(any());
    }

    @Test
    void repeatStartupWithNoUnfinishedTasksDoesNothing() {
        when(mapper.listInterruptedTasks()).thenReturn(Collections.emptyList());
        recovery.afterSingletonsInstantiated();
        verify(factory, never()).getClusterClients();
        verify(transactions, never()).getTransaction(any());
    }
}
