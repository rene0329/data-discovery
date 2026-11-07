package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.*;
import org.example.entity.*;
import org.example.factory.*;
import org.example.mapper.*;
import org.example.security.access.*;
import org.example.vo.DataItemResult;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableJobExecutionTest {
    static final String SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    DatasetRegistrationMapper datasets=mock(DatasetRegistrationMapper.class);
    NodeManagementMapper nodes=mock(NodeManagementMapper.class);
    TaskManagementMapper tasks=mock(TaskManagementMapper.class);
    RuntimeImageMapper images=mock(RuntimeImageMapper.class);
    K8sJobFactory factory=mock(K8sJobFactory.class);
    DatasetAccessAuthorizationService authorization=mock(DatasetAccessAuthorizationService.class);
    DurableTaskSchedulingService durable=mock(DurableTaskSchedulingService.class);
    FixedJobAccessSecretService secrets=mock(FixedJobAccessSecretService.class);
    KubernetesClient client=mock(KubernetesClient.class,RETURNS_DEEP_STUBS);
    AtomicReference<Job> stored=new AtomicReference<>();
    MixedOperation<Job,JobList,ScalableResource<Job>> jobApi;
    MixedOperation<Pod,PodList,PodResource> podApi;
    PodResource podResource;
    DurableTaskJob row=new DurableTaskJob();
    RuntimeImage image;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        NodeManagement node=NodeManagement.builder().nodeId(1).nodeName("node-a").type("compute").build();
        DatasetReplica replica=DatasetReplica.builder().replicaId(1L).datasetId(1L).nodeId(1).filePath("/dataset/test.npz")
                .sizeBytes(100L).checksumAlgorithm("SHA-256").checksum(SHA).build();
        when(datasets.findDatasetById(1L)).thenReturn(RegisteredDataset.builder().datasetId(1L).datasetCode("test")
                .datasetVersion("v1").status("ACTIVE").defaultRuntimeImageId(7L).build());
        when(datasets.listReplicas(1L)).thenReturn(Collections.singletonList(replica));
        when(nodes.getNodeById(1)).thenReturn(node); when(nodes.getNodeByName("node-a")).thenReturn(node);
        image=RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true).resolvedDigest("sha256:test")
                .commandJson("[\"python\"]").argsTemplateJson("[\"run.py\"]").build();
        when(images.findById(7L)).thenReturn(image);
        AccessAuthorizationResult grant=new AccessAuthorizationResult(); grant.setToken("scoped-test-token");
        when(authorization.issueInternal(any(),any())).thenReturn(grant);
        when(durable.client(any())).thenReturn(client);
        jobApi=mock(MixedOperation.class,RETURNS_SELF); ScalableResource<Job> jobResource=mock(ScalableResource.class);
        when(client.batch().v1().jobs()).thenReturn(jobApi); doReturn(jobResource).when(jobApi).withName(anyString());
        when(jobResource.get()).thenAnswer(x -> stored.get());
        podApi=mock(MixedOperation.class,RETURNS_SELF); when(client.pods()).thenReturn(podApi);
        doReturn(new PodListBuilder().build()).when(podApi).list();
        podResource=mock(PodResource.class,RETURNS_DEEP_STUBS); doReturn(podResource).when(podApi).withName(anyString());
        row.setId(1L); row.setTaskId(10); row.setDatasetId(1L); row.setReplicaId(1L); row.setExecutionMode("IN_PLACE");
        row.setTargetNode("node-a"); row.setJobName("t4-10-1-0"); row.setState("SUBMITTING"); row.setAttempt(0);
        row.setCpu(.5); row.setMemoryGi(1.0); row.setGpu(0.0); row.setEphemeralStorageGi(2.0);
        Job spec=new JobBuilder().withNewMetadata().withName(row.getJobName()).endMetadata().withNewSpec()
                .withNewTemplate().withNewSpec().addNewInitContainer().withName("data-transfer-container")
                .addNewEnv().withName("DATASET_ACCESS_TOKEN").withValue("scoped-test-token").endEnv().endInitContainer()
                .addNewContainer().withName("processing-container").addNewEnv().withName("DATA_PATH").withValue("/data/test.npz")
                .endEnv().endContainer().endSpec().endTemplate().endSpec().build();
        when(factory.createDataProcessingJob(anyString(),anyString(),anyString(),anyString(),anyString(),isNull(),
                any(),any(),any(),same(image),anyString(),any(),any())).thenReturn(new JobCreationResult(spec,client,"node-a","/data/test.npz"));
    }
    K8sTaskOrchestratorService runner() {
        K8sTaskOrchestratorService runner=new K8sTaskOrchestratorService(mock(DataManagementMapper.class),nodes,tasks,
                mock(MigrationTaskMapper.class),factory,datasets,images,new ObjectMapper(),"node-a","",Runnable::run,
                mock(DatasetReplicaAvailabilityService.class),mock(SchedulingPlanMapper.class),mock(DatasetUploadClient.class),
                authorization,mock(InPlacePlacementService.class),mock(DatasetHeatService.class));
        ReflectionTestUtils.setField(runner,"durableScheduling",durable); ReflectionTestUtils.setField(runner,"jobAccessSecrets",secrets);
        ReflectionTestUtils.setField(runner,"jobWaitTimeoutMinutes",30L); return runner;
    }
    @Test void lostCreateResponseIsRecoveredByStableIdentityWithoutResigningOrDuplicating() {
        doAnswer(x -> {
            Job created=x.getArgument(0); created.getMetadata().setUid("uid-1"); stored.set(created);
            throw new KubernetesClientException("response lost",503,null);
        }).when(jobApi).create(any(Job.class));
        assertThrows(KubernetesClientException.class,() -> runner().advanceRegisteredJob(row,null));
        assertThrows(DurableTaskSchedulingService.JobPending.class,() -> runner().advanceRegisteredJob(row,null));
        verify(jobApi,times(1)).create(any(Job.class)); verify(authorization,times(1)).issueInternal(any(),any());
        assertEquals("uid-1",row.getJobUid());
        EnvVar token=stored.get().getSpec().getTemplate().getSpec().getInitContainers().get(0).getEnv().get(0);
        assertNull(token.getValue()); assertEquals("topic4-compute-input-tokens",token.getValueFrom().getSecretKeyRef().getName());
    }
    @Test void restartObservesPreviouslyCompletedJobEvenAfterWallClockTimeout() {
        Job completed=new JobBuilder().withNewMetadata().withName(row.getJobName()).withUid("uid-1")
                .addToLabels("topic4.io/compute-job-id","1").endMetadata().withNewSpec().withNewTemplate().withNewSpec()
                .addNewContainer().withName("processing-container").addNewEnv().withName("DATA_PATH").withValue("/data/test.npz")
                .endEnv().endContainer().endSpec().endTemplate().endSpec().withNewStatus()
                .addNewCondition().withType("Complete").withStatus("True").endCondition().endStatus().build();
        stored.set(completed); row.setJobUid("uid-1"); row.setState("PREPARING"); row.setBoundAt(java.time.LocalDateTime.of(2026,1,1,0,0));
        Pod finished=new PodBuilder().withNewMetadata().withName("pod-1").endMetadata().withNewSpec().withNodeName("node-a").endSpec()
                .withNewStatus().addNewInitContainerStatus().withName("data-transfer-container").withNewState().withNewTerminated()
                .withExitCode(0).withStartedAt("2026-01-01T00:00:00Z").withFinishedAt("2026-01-01T00:00:01Z")
                .endTerminated().endState().endInitContainerStatus().addNewContainerStatus().withName("processing-container")
                .withNewState().withNewTerminated().withExitCode(0).withStartedAt("2026-01-01T00:00:01Z")
                .withFinishedAt("2026-01-01T00:00:03Z").endTerminated().endState().endContainerStatus().endStatus().build();
        doReturn(new PodListBuilder().withItems(finished).build()).when(podApi).list();
        when(podResource.inContainer("data-transfer-container").getLog()).thenReturn("TRANSFER_MS=1000\nINPUT_BYTES=100\nINPUT_SHA256="+SHA);
        when(podResource.inContainer("processing-container").getLog()).thenReturn("result=ok");
        DataItemResult result=runner().advanceRegisteredJob(row,null);
        assertEquals(1000,result.getPreparationMs()); assertEquals("node-a",result.getActualNodeName());
        assertNotNull(result.getOutputChecksumSha256()); verifyNoInteractions(authorization,secrets,factory);
    }
}
