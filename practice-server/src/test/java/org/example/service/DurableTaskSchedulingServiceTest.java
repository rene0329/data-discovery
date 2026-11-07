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
    DatasetReplicaAvailabilityService replicaAvailability=mock(DatasetReplicaAvailabilityService.class);
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
        ReflectionTestUtils.setField(service,"replicaAvailability",replicaAvailability);
        when(replicaAvailability.evaluate(any())).thenReturn(new ReplicaAvailability("AVAILABLE",true,null));
        when(jobs.listActive()).thenAnswer(x -> rows.stream().filter(j -> !j.terminal()).collect(Collectors.toList()));
        when(jobs.listByTask(anyInt())).thenAnswer(x -> rows.stream().filter(j -> j.getTaskId().equals(x.getArgument(0))).collect(Collectors.toList()));
        when(jobs.listCleanup()).thenReturn(Collections.emptyList());
        when(jobs.tasksToFinalize()).thenReturn(Collections.emptyList());
        when(tasks.getTaskByTaskId(anyInt())).thenAnswer(x -> TaskManagement.builder().taskId(x.getArgument(0)).executionMode("IN_PLACE").build());
        when(nodes.getNodeByName("node-a")).thenReturn(a); when(nodes.getNodeByName("node-b")).thenReturn(b);
        when(nodes.getNodeById(1)).thenReturn(a); when(nodes.getNodeById(2)).thenReturn(b);
        when(placement.schedulableComputeNodes()).thenReturn(Arrays.asList(a,b));
        when(images.findById(7L)).thenReturn(RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true).resolvedDigest("sha256:verified").build());
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
        when(datasets.findReplicaById(dataset)).thenReturn(replica);
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
        assertEquals("任务已取消，资源已释放",row.getWaitingReason());
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
    @Test void externalEnqueueSnapshotsParentExplicitImageAndResourceOverrides() {
        when(tasks.getTaskByTaskId(1)).thenReturn(TaskManagement.builder().taskId(1).runtimeImageId(4L)
                .resourceOverridesJson("{\"cpu\":1.0,\"memoryGi\":2.0}").build());
        SchedulingAssignment assignment=SchedulingAssignment.builder().assignmentId(20L).datasetId(1L).replicaId(1L)
                .sourceNodeId(1).targetNodeId(2).action("REMOTE_READ").build();
        service.enqueueExternal(10L,1,Collections.singletonList(assignment));
        verify(jobs).insert(argThat(job -> Long.valueOf(4).equals(job.getRuntimeImageId())
                && "{\"cpu\":1.0,\"memoryGi\":2.0}".equals(job.getOverridesJson())));
    }
    @Test void existingQueuedExternalJobUsesParentExplicitImageEvenWhenDatasetDefaultIsInvalid() {
        DurableTaskJob row=add(1,1); row.setExecutionMode("EXTERNAL"); row.setTargetNode("node-b"); row.setReplicaId(1L);
        row.setPlanId(10L); row.setAssignmentId(20L);
        when(tasks.getTaskByTaskId(1)).thenReturn(TaskManagement.builder().taskId(1).runtimeImageId(4L)
                .resourceOverridesJson("{\"cpu\":1.0,\"memoryGi\":2.0}").build());
        when(images.findById(7L)).thenReturn(null);
        when(images.findById(4L)).thenReturn(RuntimeImage.builder().runtimeImageId(4L).status("READY").enabled(true)
                .resolvedDigest("sha256:explicit-image").build());
        doAnswer(x -> { DurableTaskJob job=x.getArgument(0); job.setState("PREPARING"); throw new DurableTaskSchedulingService.JobPending(); })
                .when(runner).advanceExternalJob(any());
        reconcile(); assertEquals("PREPARING",row.getState()); assertEquals(4L,row.getRuntimeImageId());
        assertEquals(1.0,row.getCpu()); assertEquals(2.0,row.getMemoryGi());
        verify(images,never()).findById(7L); verify(runner).advanceExternalJob(row);
        verify(plans).updateAssignmentStatus(20L,"RUNNING",null);
    }
    @Test void runtimeImageWithoutResolvedDigestIsRejectedBeforeCreatingAJob() {
        DurableTaskJob row=add(1,1);
        when(images.findById(7L)).thenReturn(RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true).build());
        reconcile(); assertEquals("FAILED",row.getState()); assertNull(row.getJobName()); verifyNoInteractions(runner);
    }

    @Test void explicitExternalTargetDoesNotRequireAnyMeasuredLogicalRoute() {
        DurableTaskJob row=add(1,1); row.setExecutionMode("EXTERNAL"); row.setTargetNode("node-b"); row.setReplicaId(1L);
        when(placement.candidates(anyList(),anyList())).thenReturn(Collections.emptyList());
        doAnswer(x -> { DurableTaskJob job=x.getArgument(0); job.setState("PREPARING"); throw new DurableTaskSchedulingService.JobPending(); })
                .when(runner).advanceExternalJob(any());
        reconcile(); assertEquals("PREPARING",row.getState()); assertEquals("node-b",row.getTargetNode());
        verify(placement,never()).candidates(anyList(),anyList()); verify(runner).advanceExternalJob(row);
        verify(tasks).insertExecutionEvent(argThat(event -> event.getDetailsJson().contains("不要求逻辑拓扑测量路径")));
    }
    @Test void preparedMoveCanResumeAfterDeclaredSourceBecameMissing() {
        DurableTaskJob row=add(1,1); row.setExecutionMode("EXTERNAL"); row.setTargetNode("node-b"); row.setReplicaId(1L);
        row.setExternalPrepared(true);
        when(replicaAvailability.evaluate(any())).thenReturn(new ReplicaAvailability("MISSING",false,"source moved"));
        doAnswer(x -> { DurableTaskJob job=x.getArgument(0); job.setState("PREPARING"); throw new DurableTaskSchedulingService.JobPending(); })
                .when(runner).advanceExternalJob(any());
        reconcile(); assertEquals("PREPARING",row.getState()); verify(runner).advanceExternalJob(row);
    }
    @Test void centralizedModePrefersLocalReplicaThenOriginalReplicaOrderWithoutTopology() {
        DurableTaskJob row=add(1,1); row.setExecutionMode("CENTRALIZED"); when(runner.resolveCentralNodeName()).thenReturn("node-b");
        DatasetReplica remote=datasets.findReplicaById(1L);
        DatasetReplica local=DatasetReplica.builder().replicaId(2L).datasetId(1L).nodeId(2).sizeBytes(100L).build();
        when(datasets.listReplicas(1L)).thenReturn(Arrays.asList(remote,local));
        reconcile(); assertEquals("PREPARING",row.getState()); assertEquals(2L,row.getReplicaId());
        verify(placement,never()).candidates(anyList(),anyList());
    }
    @Test void centralizedModePreservesRemoteReplicaOrderWithoutTopology() {
        DurableTaskJob row=add(1,1); row.setExecutionMode("CENTRALIZED"); when(runner.resolveCentralNodeName()).thenReturn("node-b");
        DatasetReplica first=DatasetReplica.builder().replicaId(99L).datasetId(1L).nodeId(1).sizeBytes(100L).build();
        DatasetReplica second=datasets.findReplicaById(1L);
        when(datasets.listReplicas(1L)).thenReturn(Arrays.asList(first,second));
        reconcile(); assertEquals("PREPARING",row.getState()); assertEquals(99L,row.getReplicaId());
        verify(placement,never()).candidates(anyList(),anyList());
    }
    @Test void externalUnavailableSourceRemainsWaitingBeforePreparation() {
        DurableTaskJob row=add(1,1); row.setExecutionMode("EXTERNAL"); row.setTargetNode("node-b"); row.setReplicaId(1L);
        when(replicaAvailability.evaluate(any())).thenReturn(new ReplicaAvailability("MISSING",false,"source missing"));
        reconcile(); assertEquals("WAITING",row.getState()); assertNull(row.getJobName());
        verify(runner,never()).advanceExternalJob(any());
    }

    @Test void asynchronousExternalPreparationDoesNotBlockOtherTasks() {
        DurableTaskJob externalRow=add(1,1); externalRow.setExecutionMode("EXTERNAL"); externalRow.setState("PREPARING");
        externalRow.setTargetNode("node-a"); externalRow.setCpu(.5); externalRow.setMemoryGi(1.0); externalRow.setGpu(0.0); externalRow.setEphemeralStorageGi(2.0);
        when(external.isPreparing(externalRow.getId())).thenReturn(true);
        DurableTaskJob ordinary=add(2,2); reconcile(); assertEquals("PREPARING",ordinary.getState());
        verify(runner,never()).advanceExternalJob(any());
    }
}
