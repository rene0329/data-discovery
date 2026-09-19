package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.RuntimeImage;
import org.example.factory.JobCreationResult;
import org.example.factory.K8sJobFactory;
import org.example.mapper.DataManagementMapper;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.MigrationTaskMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.RuntimeImageMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.DatasetAccessAuthorizationService;
import org.example.vo.DataItemResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class K8sTaskOrchestratorServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void inPlaceModeRunsARealJobWaitsForComputeAndPersistsEvidence() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        RuntimeImageMapper images = mock(RuntimeImageMapper.class);
        K8sJobFactory jobs = mock(K8sJobFactory.class);
        DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
        DatasetAccessAuthorizationService authorization = mock(DatasetAccessAuthorizationService.class);
        MigrationTaskMapper migrations = mock(MigrationTaskMapper.class);
        NodeManagement source = NodeManagement.builder().nodeId(3).nodeName("node-a")
                .type("compute-storage").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(20L).datasetId(10L).nodeId(3)
                .filePath("/dataset/test.npz").sizeBytes(100L).checksumAlgorithm("SHA-256")
                .checksum("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").build();
        when(datasets.findDatasetById(10L)).thenReturn(RegisteredDataset.builder().datasetId(10L)
                .datasetCode("test").datasetVersion("v1").status("ACTIVE")
                .defaultRuntimeImageId(7L).build());
        when(datasets.listReplicas(10L)).thenReturn(Collections.singletonList(replica));
        when(availability.evaluate(replica)).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodes.getComputeCapableNodes()).thenReturn(Collections.singletonList(source));
        when(nodes.getNodeById(3)).thenReturn(source);
        when(nodes.getNodeByName("node-a")).thenReturn(source);
        RuntimeImage image = RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true)
                .resolvedDigest("sha256:image").commandJson("[\"python\"]")
                .argsTemplateJson("[\"run.py\"]").build();
        when(images.findById(7L)).thenReturn(image);
        AccessAuthorizationResult grant = new AccessAuthorizationResult();
        grant.setToken("scoped-read-token");
        when(authorization.issueInternal(any(), any())).thenReturn(grant);

        KubernetesClient client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class, RETURNS_SELF);
        MixedOperation<Job, JobList, ScalableResource<Job>> jobApi = mock(MixedOperation.class, RETURNS_SELF);
        ScalableResource<Job> jobResource = mock(ScalableResource.class);
        PodResource podResource = mock(PodResource.class, RETURNS_DEEP_STUBS);
        when(client.pods()).thenReturn(pods);
        when(client.batch().v1().jobs()).thenReturn(jobApi);
        doReturn(jobResource).when(jobApi).withName(anyString());
        doReturn(podResource).when(pods).withName(anyString());
        when(podResource.inContainer("data-transfer-container").getLog())
                .thenReturn("TRANSFER_MS=1000\nINPUT_BYTES=100\nINPUT_SHA256="
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(podResource.inContainer("processing-container").getLog()).thenReturn("result=ok");
        Pod completedPod = new PodBuilder().withNewMetadata().withName("task-pod").endMetadata()
                .withNewSpec().withNodeName("node-a").endSpec()
                .withNewStatus()
                .addNewInitContainerStatus().withName("data-transfer-container")
                .withNewState().withNewTerminated().withExitCode(0)
                .withStartedAt("2026-09-19T00:00:00Z").withFinishedAt("2026-09-19T00:00:01Z")
                .endTerminated().endState().endInitContainerStatus()
                .addNewContainerStatus().withName("processing-container")
                .withNewState().withNewTerminated().withExitCode(0)
                .withStartedAt("2026-09-19T00:00:01Z").withFinishedAt("2026-09-19T00:00:03Z")
                .endTerminated().endState().endContainerStatus().endStatus().build();
        doReturn(new PodListBuilder().withItems(completedPod).build()).when(pods).list();
        when(jobResource.get()).thenReturn(new JobBuilder().withNewStatus().addNewCondition()
                .withType("Complete").withStatus("True").endCondition().endStatus().build());
        when(jobs.createDataProcessingJob(anyString(), eq("node-a"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token")))
                .thenReturn(new JobCreationResult(new JobBuilder().build(), client, "node-a", "/data/test.npz"));

        K8sTaskOrchestratorService service = new K8sTaskOrchestratorService(
                mock(DataManagementMapper.class), nodes, tasks, migrations, jobs, datasets, images,
                new ObjectMapper(), "node-a", "", Runnable::run, availability,
                mock(SchedulingPlanMapper.class), mock(DatasetUploadClient.class),
                mock(NetworkTopologyService.class), authorization);

        service.executeRegisteredTask(30, Collections.singletonList(10L), 7L, null, "IN_PLACE");

        verify(jobApi).create(any(Job.class));
        verify(podResource, atLeastOnce()).inContainer("processing-container");
        verify(authorization).issueInternal(argThat(scope -> "10".equals(scope.getDatasetId())
                && "v1".equals(scope.getDatasetVersion())
                && "/dataset/test.npz".equals(scope.getPath())
                && "READ".equals(scope.getAction())
                && "node-a".equals(scope.getTargetNode())), any());
        verify(tasks, times(4)).insertExecutionEvent(any());
        verify(tasks).updateExecutionSummary(argThat(summary -> "已完成".equals(summary.getStatus())
                && Long.valueOf(1000L).equals(summary.getDataPreparationMs())
                && Long.valueOf(2000L).equals(summary.getComputeDurationMs())
                && Boolean.TRUE.equals(summary.getExecutionEvidenceComplete())));
    }

    @Test
    void prefersReplicaAlreadyLocatedOnComputeNode() {
        DatasetReplica storageReplica = DatasetReplica.builder()
                .replicaId(19L)
                .nodeId(6)
                .build();
        DatasetReplica computeReplica = DatasetReplica.builder()
                .replicaId(20L)
                .nodeId(3)
                .build();

        DatasetReplica selected = K8sTaskOrchestratorService.selectPreferredSourceReplica(
                Arrays.asList(storageReplica, computeReplica), Collections.singleton(3))
                .orElseThrow(AssertionError::new);

        assertEquals(20L, selected.getReplicaId());
    }

    @Test
    void preservesReplicaOrderWhenNoComputeReplicaExists() {
        DatasetReplica first = DatasetReplica.builder().replicaId(19L).nodeId(6).build();
        DatasetReplica second = DatasetReplica.builder().replicaId(21L).nodeId(4).build();

        DatasetReplica selected = K8sTaskOrchestratorService.selectPreferredSourceReplica(
                Arrays.asList(first, second), Collections.emptySet())
                .orElseThrow(AssertionError::new);

        assertEquals(19L, selected.getReplicaId());
    }

    @Test
    void aggregatesParallelFilesAsWallClockInsteadOfSummingDurations() {
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        K8sTaskOrchestratorService service = new K8sTaskOrchestratorService(
                mock(DataManagementMapper.class), mock(NodeManagementMapper.class), tasks,
                mock(MigrationTaskMapper.class), mock(K8sJobFactory.class),
                mock(DatasetRegistrationMapper.class), mock(RuntimeImageMapper.class),
                new ObjectMapper(), "node-a", "", Runnable::run,
                mock(DatasetReplicaAvailabilityService.class), mock(SchedulingPlanMapper.class),
                mock(DatasetUploadClient.class), mock(NetworkTopologyService.class),
                mock(DatasetAccessAuthorizationService.class));
        DataItemResult first = evidence("2026-09-19T00:00:00Z", "2026-09-19T00:00:04Z",
                "2026-09-19T00:00:04Z", "2026-09-19T00:00:08Z");
        DataItemResult second = evidence("2026-09-19T00:00:01Z", "2026-09-19T00:00:03Z",
                "2026-09-19T00:00:03Z", "2026-09-19T00:00:06Z");

        ReflectionTestUtils.invokeMethod(service, "updateRegisteredTaskStatus", 30, "IN_PLACE",
                Arrays.asList("first", "second"), Arrays.asList(first, second), 2);

        verify(tasks).updateExecutionSummary(argThat(summary -> "已完成".equals(summary.getStatus())
                && Long.valueOf(4000L).equals(summary.getDataPreparationMs())
                && Long.valueOf(5000L).equals(summary.getComputeDurationMs())
                && Boolean.TRUE.equals(summary.getExecutionEvidenceComplete())));
    }

    @Test
    void centralizedModeCreatesARealJobPinnedToTheConfiguredCentralNode() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        RuntimeImageMapper images = mock(RuntimeImageMapper.class);
        K8sJobFactory jobs = mock(K8sJobFactory.class);
        DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
        DatasetAccessAuthorizationService authorization = mock(DatasetAccessAuthorizationService.class);
        NodeManagement source = NodeManagement.builder().nodeId(2).nodeName("node-b").build();
        NodeManagement central = NodeManagement.builder().nodeId(3).nodeName("node-a").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(20L).datasetId(10L).nodeId(2)
                .filePath("/dataset/test.npz").sizeBytes(100L).checksumAlgorithm("SHA-256")
                .checksum("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").build();
        when(datasets.findDatasetById(10L)).thenReturn(RegisteredDataset.builder().datasetId(10L)
                .datasetCode("test").datasetVersion("v1").status("ACTIVE")
                .defaultRuntimeImageId(7L).build());
        when(datasets.listReplicas(10L)).thenReturn(Collections.singletonList(replica));
        when(availability.evaluate(replica)).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodes.getComputeCapableNodes()).thenReturn(Arrays.asList(source, central));
        when(nodes.getNodeById(2)).thenReturn(source);
        when(nodes.getNodeByName("node-a")).thenReturn(central);
        RuntimeImage image = RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true)
                .resolvedDigest("sha256:image").commandJson("[\"python\"]")
                .argsTemplateJson("[\"run.py\"]").build();
        when(images.findById(7L)).thenReturn(image);
        AccessAuthorizationResult grant = new AccessAuthorizationResult();
        grant.setToken("scoped-read-token");
        when(authorization.issueInternal(any(), any())).thenReturn(grant);
        when(jobs.createDataProcessingJob(anyString(), eq("node-b"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"))).thenThrow(new IllegalStateException("unit test stop"));
        K8sTaskOrchestratorService service = new K8sTaskOrchestratorService(
                mock(DataManagementMapper.class), nodes, tasks, mock(MigrationTaskMapper.class),
                jobs, datasets, images, new ObjectMapper(), "node-a", "", Runnable::run,
                availability, mock(SchedulingPlanMapper.class), mock(DatasetUploadClient.class),
                mock(NetworkTopologyService.class), authorization);

        service.executeRegisteredTask(30, Collections.singletonList(10L), 7L, null, "CENTRALIZED");

        verify(jobs).createDataProcessingJob(anyString(), eq("node-b"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"));
        verify(tasks, never()).updateTask(any());
        verify(tasks).updateExecutionSummary(argThat(summary -> "执行失败".equals(summary.getStatus())
                && Boolean.FALSE.equals(summary.getExecutionEvidenceComplete())));
    }

    private DataItemResult evidence(String preparationStart, String preparationReady,
                                    String computeStart, String computeFinished) {
        DataItemResult result = new DataItemResult();
        result.setPreparationStartedAt(Instant.parse(preparationStart));
        result.setPreparationReadyAt(Instant.parse(preparationReady));
        result.setComputeStartedAt(Instant.parse(computeStart));
        result.setComputeFinishedAt(Instant.parse(computeFinished));
        result.setActualNodeName("node-a");
        result.setInputBytes(100L);
        result.setInputChecksumSha256(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        result.setOutputChecksumSha256(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        return result;
    }
}
