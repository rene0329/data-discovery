package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.entity.*;
import org.example.factory.K8sJobFactory;
import org.example.mapper.*;
import org.example.vo.DataItemResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableTaskSchedulingServiceTest {
    DurableTaskJobMapper jobs=mock(DurableTaskJobMapper.class);
    TaskManagementMapper tasks=mock(TaskManagementMapper.class);
    DatasetRegistrationMapper datasets=mock(DatasetRegistrationMapper.class);
    NodeManagementMapper nodes=mock(NodeManagementMapper.class);
    SchedulingPlanMapper plans=mock(SchedulingPlanMapper.class);
    InPlacePlacementService placement=mock(InPlacePlacementService.class);
    NodeResourceService resources=mock(NodeResourceService.class);
    K8sJobFactory factory=mock(K8sJobFactory.class);
    K8sTaskOrchestratorService runner=mock(K8sTaskOrchestratorService.class);
    ExternalDurableJobHandler external=mock(ExternalDurableJobHandler.class);
    KubernetesClient client=mock(KubernetesClient.class,RETURNS_DEEP_STUBS);
    RuntimeImageMapper images=mock(RuntimeImageMapper.class);
    DurableTaskSchedulingService service;
    List<DurableTaskJob> rows=new ArrayList<>();
    NodeManagement a=NodeManagement.builder().nodeId(1).nodeName("node-a").cluster("main").type("compute").build();
    NodeManagement b=NodeManagement.builder().nodeId(2).nodeName("node-b").cluster("main").type("compute").build();
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        ObjectProvider<K8sTaskOrchestratorService> runners=mock(ObjectProvider.class);
        ObjectProvider<ExternalDurableJobHandler> handlers=mock(ObjectProvider.class);
        when(runners.getObject()).thenReturn(runner); when(handlers.getObject()).thenReturn(external);
        service=new DurableTaskSchedulingService(jobs,tasks,datasets,nodes,plans,placement,resources,factory,
                new ObjectMapper(),mock(DataSource.class),runners,handlers);
        ReflectionTestUtils.setField(service,"images",images);
        ReflectionTestUtils.setField(service,"migrations",mock(MigrationTaskMapper.class));
        when(jobs.listActive()).thenAnswer(x -> rows.stream().filter(j -> !j.terminal()).collect(Collectors.toList()));
        when(jobs.listByTask(anyInt())).thenAnswer(x -> rows.stream().filter(j -> j.getTaskId().equals(x.getArgument(0))).collect(Collectors.toList()));
        when(jobs.listCleanup()).thenReturn(Collections.emptyList());
        when(jobs.tasksToFinalize()).thenReturn(Collections.emptyList());
        when(tasks.getTaskByTaskId(anyInt())).thenAnswer(x -> TaskManagement.builder().taskId(x.getArgument(0)).executionMode("IN_PLACE").build());
        when(nodes.getNodeByName("node-a")).thenReturn(a); when(nodes.getNodeByName("node-b")).thenReturn(b);
        when(nodes.getNodeById(1)).thenReturn(a); when(nodes.getNodeById(2)).thenReturn(b);
        when(placement.schedulableComputeNodes()).thenReturn(Arrays.asList(a,b));
        when(images.findById(7L)).thenReturn(RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true).build());
        when(factory.getClusterClients()).thenReturn(Collections.singletonMap("main",client));
        MixedOperation<Job,JobList,ScalableResource<Job>> jobApi=mock(MixedOperation.class,RETURNS_SELF);
        ScalableResource<Job> jobResource=mock(ScalableResource.class);
        when(client.batch().v1().jobs()).thenReturn(jobApi);
        doReturn(jobResource).when(jobApi).withName(anyString());
        MixedOperation<Pod,PodList,PodResource> podApi=mock(MixedOperation.class,RETURNS_SELF);
        when(client.pods()).thenReturn(podApi);
        doReturn(new PodListBuilder().build()).when(podApi).list();
        when(resources.snapshot(any())).thenAnswer(x -> ledger(1,1));
        doAnswer(x -> { DurableTaskJob row=x.getArgument(0); row.setState("PREPARING"); throw new DurableTaskSchedulingService.JobPending(); })
                .when(runner).advanceRegisteredJob(any(),any());
        doAnswer(x -> { DurableTaskJob row=x.getArgument(0); row.setState("PREPARING"); throw new DurableTaskSchedulingService.JobPending(); })
                .when(runner).advanceRegisteredJob(any(),isNull());
    }
    NodeResourceLedger ledger(double cpuA,double cpuB) {
        NodeResourceLedger ledger=new NodeResourceLedger();
        ledger.put(NodeResourceLedger.key(a),cpuA,2,0,10); ledger.putCapacity(NodeResourceLedger.key(a),1,2,0,10);
        ledger.put(NodeResourceLedger.key(b),cpuB,2,0,10); ledger.putCapacity(NodeResourceLedger.key(b),1,2,0,10); return ledger;
    }
    DurableTaskJob add(int task,long dataset) {
        DurableTaskJob row=new DurableTaskJob(); row.setId((long)rows.size()+1); row.setTaskId(task); row.setDatasetId(dataset);
        row.setExecutionMode("IN_PLACE"); row.setState("WAITING"); row.setAttempt(0); row.setQueuedAt(DurableTaskSchedulingService.now()); rows.add(row);
        RegisteredDataset data=RegisteredDataset.builder().datasetId(dataset).status("ACTIVE").requiredCpu(.5).requiredMemoryGi(1.0).defaultRuntimeImageId(7L).build();
        when(datasets.findDatasetById(dataset)).thenReturn(data);
        DatasetReplica replica=DatasetReplica.builder().replicaId(dataset).datasetId(dataset).nodeId(1).sizeBytes(100L).build();
        when(datasets.listReplicas(dataset)).thenReturn(Collections.singletonList(replica));
        List<InPlacePlacementService.Placement> candidates=Arrays.asList(
                new InPlacePlacementService.Placement(replica,a,a,InPlacePlacementService.Tier.LOCAL,null,Collections.emptyList(),null),
                new InPlacePlacementService.Placement(replica,a,b,InPlacePlacementService.Tier.NEAREST,null,Collections.emptyList(),null));
        when(placement.candidates(eq(Collections.singletonList(replica)),anyList())).thenReturn(candidates); return row;
    }
    void reconcile() { ReflectionTestUtils.invokeMethod(service,"reconcile"); }
    @Test void fiveTasksUseBothNodesAndFifthWaitsWithoutBlocking() {
        for(int i=1;i<=5;i++) add(i,i);
        reconcile();
        assertEquals(Arrays.asList("node-a","node-a","node-b","node-b",null),rows.stream().map(DurableTaskJob::getTargetNode).collect(Collectors.toList()));
        assertEquals("WAITING",rows.get(4).getState()); assertNotNull(rows.get(4).getWaitingReason());
        verify(runner,times(4)).advanceRegisteredJob(any(),isNull());
    }
    @Test void allResourcesExhaustedCreatesNoJobAndSignsNoCredentials() {
        add(1,1); when(resources.snapshot(any())).thenReturn(ledger(0,0)); reconcile();
        assertEquals("WAITING",rows.get(0).getState()); assertNull(rows.get(0).getJobName()); verifyNoInteractions(runner);
    }
    @Test void unboundIntentReservesCapacityBeforeFollowingTask() {
        DurableTaskJob active=add(1,1); active.setTargetNode("node-a"); active.setClusterId("main"); active.setState("PREPARING");
        active.setJobName("existing"); active.setCpu(.5); active.setMemoryGi(1.0); active.setGpu(0.0); active.setEphemeralStorageGi(2.0);
        DurableTaskJob queued=add(2,2); when(resources.snapshot(any())).thenReturn(ledger(.5,1)); reconcile();
        assertEquals("node-b",queued.getTargetNode());
    }
    @Test void unknownCapacityWaitsInsteadOfPermanentFailure() {
        DurableTaskJob row=add(1,1); NodeResourceLedger snapshot=new NodeResourceLedger();
        snapshot.put(NodeResourceLedger.key(a),0,0,0,0); snapshot.putCapacity(NodeResourceLedger.key(a),0,0,0,0);
        when(resources.snapshot(any())).thenReturn(snapshot); reconcile(); assertEquals("WAITING",row.getState());
    }
    @Test void impossibleResourceRequestFailsWithoutSubmission() {
        DurableTaskJob row=add(1,1); when(datasets.findDatasetById(1L)).thenReturn(RegisteredDataset.builder().datasetId(1L)
                .status("ACTIVE").requiredCpu(8.0).requiredMemoryGi(1.0).defaultRuntimeImageId(7L).build());
        reconcile(); assertEquals("FAILED",row.getState()); assertNull(row.getJobName()); verifyNoInteractions(runner);
    }
    @Test void earlierLargeWaitingJobDoesNotBlockSmallLaterJob() {
        DurableTaskJob large=add(1,1); when(datasets.findDatasetById(1L)).thenReturn(RegisteredDataset.builder().datasetId(1L)
                .status("ACTIVE").requiredCpu(1.0).requiredMemoryGi(1.0).defaultRuntimeImageId(7L).build());
        DurableTaskJob small=add(2,2); when(resources.snapshot(any())).thenReturn(ledger(.5,.5)); reconcile();
        assertEquals("WAITING",large.getState()); assertEquals("PREPARING",small.getState());
    }
    @Test void terminalCleanupIsRetriedEvenAfterTaskWasFinalized() {
        DurableTaskJob row=add(1,1); row.setState("COMPLETED"); row.setJobName("finished"); row.setClusterId("main");
        when(jobs.listCleanup()).thenReturn(Collections.singletonList(row));
        when(client.batch().v1().jobs().inNamespace("default").withName("finished").get()).thenReturn(null);
        when(client.pods().inNamespace("default").withLabel("job-name","finished").list()).thenReturn(new PodListBuilder().build());
        reconcile(); assertNotNull(row.getCleanedAt());
    }
    @Test void cancellationWaitsForEveryPodToDisappear() {
        DurableTaskJob row=add(1,1); row.setState("CANCELLING"); row.setJobName("cancelling"); row.setClusterId("main");
        when(client.batch().v1().jobs().inNamespace("default").withName("cancelling").get()).thenReturn(null);
        when(client.pods().inNamespace("default").withLabel("job-name","cancelling").list()).thenReturn(
                new PodListBuilder().addNewItem().withNewMetadata().withName("still-terminating").endMetadata().endItem().build());
        reconcile(); assertEquals("CANCELLING",row.getState());
        when(client.pods().inNamespace("default").withLabel("job-name","cancelling").list()).thenReturn(new PodListBuilder().build());
        reconcile(); assertEquals("CANCELLED",row.getState()); assertNotNull(row.getFinishedAt());
    }
    @Test void cancellingQueuedWorkDoesNotCreateAJob() {
        DurableTaskJob row=add(1,1); row.setState("CANCELLING"); reconcile();
        assertEquals("CANCELLED",row.getState()); verifyNoInteractions(factory,runner);
    }
    @Test void deletionRejectsForeignJobEvenWhenCreateResponseLost() {
        DurableTaskJob row=add(1,1); row.setJobName("collision"); row.setClusterId("main");
        when(client.batch().v1().jobs().inNamespace("default").withName("collision").get())
                .thenReturn(new JobBuilder().withNewMetadata().withName("collision").withUid("other").endMetadata().build());
        assertThrows(IllegalStateException.class,() -> service.deleteAndGone(row));
    }
    @Test void asynchronousExternalPreparationDoesNotBlockOtherTasks() {
        DurableTaskJob externalRow=add(1,1); externalRow.setExecutionMode("EXTERNAL"); externalRow.setState("PREPARING");
        externalRow.setTargetNode("node-a"); externalRow.setCpu(.5); externalRow.setMemoryGi(1.0); externalRow.setGpu(0.0); externalRow.setEphemeralStorageGi(2.0);
        when(external.isPreparing(externalRow.getId())).thenReturn(true);
        DurableTaskJob ordinary=add(2,2); reconcile(); assertEquals("PREPARING",ordinary.getState());
        verify(runner,never()).advanceExternalJob(any());
    }
}
