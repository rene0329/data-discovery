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
import org.example.entity.TaskExecutionEvent;
import org.example.entity.TaskManagement;
import org.example.factory.JobCreationResult;
import org.example.factory.K8sJobFactory;
import org.example.mapper.DataManagementMapper;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.MigrationTaskMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.RuntimeImageMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.security.access.AccessAuditContext;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.DatasetAccessAuthorizationService;
import org.example.vo.DataItemResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class K8sTaskOrchestratorServiceTest {
    private static final String SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

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
        DatasetHeatService heat = mock(DatasetHeatService.class);
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
                authorization, placement(nodes, availability), heat);

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
        verify(heat).recordAccess(10L);
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
                mock(DatasetUploadClient.class),
                mock(DatasetAccessAuthorizationService.class), mock(InPlacePlacementService.class),
                mock(DatasetHeatService.class));
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
        DatasetHeatService heat = mock(DatasetHeatService.class);
        K8sTaskOrchestratorService service = new K8sTaskOrchestratorService(
                mock(DataManagementMapper.class), nodes, tasks, mock(MigrationTaskMapper.class),
                jobs, datasets, images, new ObjectMapper(), "node-a", "", Runnable::run,
                availability, mock(SchedulingPlanMapper.class), mock(DatasetUploadClient.class),
                authorization, placement(nodes, availability), heat);

        service.executeRegisteredTask(30, Collections.singletonList(10L), 7L, null, "CENTRALIZED");

        verify(jobs).createDataProcessingJob(anyString(), eq("node-b"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"));
        verify(tasks, never()).updateTask(any());
        verify(tasks).updateExecutionSummary(argThat(summary -> "执行失败".equals(summary.getStatus())
                && Boolean.FALSE.equals(summary.getExecutionEvidenceComplete())));
        verifyNoInteractions(heat);
    }

    @Test
    void comparisonTaskDispatchesBothModesForEveryDatasetUnderOneTaskId() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        RuntimeImageMapper images = mock(RuntimeImageMapper.class);
        K8sJobFactory jobs = mock(K8sJobFactory.class);
        DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
        DatasetAccessAuthorizationService authorization = mock(DatasetAccessAuthorizationService.class);
        NodeManagement central = NodeManagement.builder().nodeId(3).nodeName("node-a").type("compute").build();
        NodeManagement nodeB = NodeManagement.builder().nodeId(2).nodeName("node-b").type("compute-storage").build();
        NodeManagement nodeC = NodeManagement.builder().nodeId(4).nodeName("node-c").type("compute-storage").build();
        when(nodes.getComputeCapableNodes()).thenReturn(Arrays.asList(central, nodeB, nodeC));
        when(nodes.getNodeById(2)).thenReturn(nodeB);
        when(nodes.getNodeById(4)).thenReturn(nodeC);
        when(nodes.getNodeByName("node-a")).thenReturn(central);
        registerDataset(datasets, availability, 10L, "mnist", 2);
        registerDataset(datasets, availability, 11L, "cifar", 4);
        RuntimeImage image = RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true)
                .resolvedDigest("sha256:image").commandJson("[\"python\"]")
                .argsTemplateJson("[\"run.py\"]").build();
        when(images.findById(7L)).thenReturn(image);
        AccessAuthorizationResult grant = new AccessAuthorizationResult();
        grant.setToken("scoped-read-token");
        when(authorization.issueInternal(any(), any())).thenReturn(grant);
        when(jobs.createDataProcessingJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                isNull(), any(), any(), any(), same(image), eq("scoped-read-token")))
                .thenThrow(new IllegalStateException("unit test stop"));
        DatasetHeatService heat = mock(DatasetHeatService.class);
        K8sTaskOrchestratorService service = new K8sTaskOrchestratorService(
                mock(DataManagementMapper.class), nodes, tasks, mock(MigrationTaskMapper.class),
                jobs, datasets, images, new ObjectMapper(), "node-a", "", Runnable::run,
                availability, mock(SchedulingPlanMapper.class), mock(DatasetUploadClient.class),
                authorization, placement(nodes, availability), heat);

        service.executeRegisteredTask(30, Arrays.asList(10L, 11L), 7L, null, "COMPARISON");

        // IN_PLACE runs on the replica's own compute node, CENTRALIZED on the central node.
        verify(jobs).createDataProcessingJob(startsWith("in-place-"), eq("node-b"), eq("mnist.npz"),
                eq("/dataset/mnist.npz"), eq("node-b"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"));
        verify(jobs).createDataProcessingJob(startsWith("centralized-"), eq("node-b"), eq("mnist.npz"),
                eq("/dataset/mnist.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"));
        verify(jobs).createDataProcessingJob(startsWith("in-place-"), eq("node-c"), eq("cifar.npz"),
                eq("/dataset/cifar.npz"), eq("node-c"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"));
        verify(jobs).createDataProcessingJob(startsWith("centralized-"), eq("node-c"), eq("cifar.npz"),
                eq("/dataset/cifar.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"));
        verify(jobs, times(4)).createDataProcessingJob(anyString(), anyString(), anyString(), anyString(),
                anyString(), isNull(), any(), any(), any(), any(), anyString());

        ArgumentCaptor<TaskExecutionEvent> events = ArgumentCaptor.forClass(TaskExecutionEvent.class);
        verify(tasks, times(4)).insertExecutionEvent(events.capture());
        assertTrue(events.getAllValues().stream().allMatch(event -> Integer.valueOf(30).equals(event.getTaskId())
                && "JOB_FAILED".equals(event.getEventType())));
        assertEquals(new HashSet<>(Arrays.asList("10/IN_PLACE", "10/CENTRALIZED", "11/IN_PLACE", "11/CENTRALIZED")),
                events.getAllValues().stream().map(event -> event.getDatasetId() + "/" + event.getExecutionMode())
                        .collect(Collectors.toSet()));

        verify(tasks).updateExecutionSummary(argThat(summary -> "执行中".equals(summary.getStatus())
                && summary.getStartedAt() != null));
        ArgumentCaptor<TaskManagement> finalRow = ArgumentCaptor.forClass(TaskManagement.class);
        verify(tasks).updateTask(finalRow.capture());
        assertEquals(30, finalRow.getValue().getTaskId());
        assertEquals("执行失败", finalRow.getValue().getStatus());
        assertNull(finalRow.getValue().getT1());
        assertNull(finalRow.getValue().getT2());
        assertNull(finalRow.getValue().getRating());
        assertEquals(Boolean.FALSE, finalRow.getValue().getExecutionEvidenceComplete());
        assertNotNull(finalRow.getValue().getFinishedAt());
        assertEquals("分布式调度方案:\nmnist: 执行失败\ncifar: 执行失败\n"
                + "中心化调度方案:\nmnist: 执行失败\ncifar: 执行失败", finalRow.getValue().getSchedule());
        verifyNoInteractions(heat);
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparisonJobsForTheSameDatasetOnTheSameNodeDoNotShareKeys() {
        // The central node is also the dataset's in-place node, so both jobs of one
        // dataset land on the same node under the same task ID.
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        RuntimeImageMapper images = mock(RuntimeImageMapper.class);
        K8sJobFactory jobs = mock(K8sJobFactory.class);
        DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
        DatasetAccessAuthorizationService authorization = mock(DatasetAccessAuthorizationService.class);
        NodeManagement nodeA = NodeManagement.builder().nodeId(3).nodeName("node-a")
                .type("compute-storage").build();
        when(nodes.getComputeCapableNodes()).thenReturn(Collections.singletonList(nodeA));
        when(nodes.getNodeById(3)).thenReturn(nodeA);
        when(nodes.getNodeByName("node-a")).thenReturn(nodeA);
        registerDataset(datasets, availability, 10L, "test", 3);
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
                .thenReturn("TRANSFER_MS=1000\nINPUT_BYTES=100\nINPUT_SHA256=" + SHA256);
        when(podResource.inContainer("processing-container").getLog()).thenReturn("result=ok");
        doReturn(new PodListBuilder().withItems(completedPod("node-a")).build()).when(pods).list();
        when(jobResource.get()).thenReturn(new JobBuilder().withNewStatus().addNewCondition()
                .withType("Complete").withStatus("True").endCondition().endStatus().build());
        when(jobs.createDataProcessingJob(anyString(), eq("node-a"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token")))
                .thenReturn(new JobCreationResult(new JobBuilder().build(), client, "node-a", "/data/test.npz"));
        DatasetHeatService heat = mock(DatasetHeatService.class);
        K8sTaskOrchestratorService service = new K8sTaskOrchestratorService(
                mock(DataManagementMapper.class), nodes, tasks, mock(MigrationTaskMapper.class),
                jobs, datasets, images, new ObjectMapper(), "node-a", "", Runnable::run,
                availability, mock(SchedulingPlanMapper.class), mock(DatasetUploadClient.class),
                authorization, placement(nodes, availability), heat);

        service.executeRegisteredTask(30, Collections.singletonList(10L), 7L, null, "COMPARISON");

        // Kubernetes Job name (also the pod job-name/app label): mode prefix plus random suffix.
        ArgumentCaptor<String> jobNames = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(2)).createDataProcessingJob(jobNames.capture(), eq("node-a"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"));
        List<String> names = jobNames.getAllValues();
        assertNotEquals(names.get(0), names.get(1));
        String inPlaceJob = names.stream().filter(name -> name.startsWith("in-place-")).findFirst()
                .orElseThrow(AssertionError::new);
        String centralizedJob = names.stream().filter(name -> name.startsWith("centralized-")).findFirst()
                .orElseThrow(AssertionError::new);
        verify(jobApi, times(2)).create(any(Job.class));

        // Access-token audit request IDs are derived from task ID + job name.
        ArgumentCaptor<AccessAuditContext> audits = ArgumentCaptor.forClass(AccessAuditContext.class);
        verify(authorization, times(2)).issueInternal(any(), audits.capture());
        assertEquals(new HashSet<>(Arrays.asList("task-30-" + inPlaceJob, "task-30-" + centralizedJob)),
                audits.getAllValues().stream().map(AccessAuditContext::getRequestId).collect(Collectors.toSet()));

        // Execution evidence is keyed by (task, dataset, mode, job).
        ArgumentCaptor<TaskExecutionEvent> events = ArgumentCaptor.forClass(TaskExecutionEvent.class);
        verify(tasks, times(8)).insertExecutionEvent(events.capture());
        Map<String, Set<String>> jobsByMode = events.getAllValues().stream().collect(Collectors.groupingBy(
                TaskExecutionEvent::getExecutionMode,
                Collectors.mapping(TaskExecutionEvent::getJobName, Collectors.toSet())));
        assertEquals(Collections.singleton(inPlaceJob), jobsByMode.get("IN_PLACE"));
        assertEquals(Collections.singleton(centralizedJob), jobsByMode.get("CENTRALIZED"));
        assertEquals(2, jobsByMode.size());

        ArgumentCaptor<TaskManagement> finalRow = ArgumentCaptor.forClass(TaskManagement.class);
        verify(tasks).updateTask(finalRow.capture());
        assertEquals("已完成", finalRow.getValue().getStatus());
        assertEquals(1.0, finalRow.getValue().getT1());
        assertEquals(1.0, finalRow.getValue().getT2());
        assertEquals(1.0, finalRow.getValue().getRating());
        assertEquals(Boolean.TRUE, finalRow.getValue().getExecutionEvidenceComplete());
        assertEquals("分布式调度方案:\ntest: node-a -> node-a\n中心化调度方案:\ntest: node-a -> node-a",
                finalRow.getValue().getSchedule());
        // Both modes read the dataset, but it is one task and so one access.
        verify(heat).recordAccess(10L);
        verifyNoMoreInteractions(heat);
    }

    @Test
    void comparisonCountsADatasetThatEitherModeRead() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        RuntimeImageMapper images = mock(RuntimeImageMapper.class);
        K8sJobFactory jobs = mock(K8sJobFactory.class);
        DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
        DatasetAccessAuthorizationService authorization = mock(DatasetAccessAuthorizationService.class);
        DatasetHeatService heat = mock(DatasetHeatService.class);
        NodeManagement nodeA = NodeManagement.builder().nodeId(3).nodeName("node-a")
                .type("compute-storage").build();
        when(nodes.getComputeCapableNodes()).thenReturn(Collections.singletonList(nodeA));
        when(nodes.getNodeById(3)).thenReturn(nodeA);
        when(nodes.getNodeByName("node-a")).thenReturn(nodeA);
        registerDataset(datasets, availability, 10L, "test", 3);
        RuntimeImage image = RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true)
                .resolvedDigest("sha256:image").commandJson("[\"python\"]")
                .argsTemplateJson("[\"run.py\"]").build();
        when(images.findById(7L)).thenReturn(image);
        AccessAuthorizationResult grant = new AccessAuthorizationResult();
        grant.setToken("scoped-read-token");
        when(authorization.issueInternal(any(), any())).thenReturn(grant);
        KubernetesClient client = completedJobClient("node-a");
        when(jobs.createDataProcessingJob(startsWith("in-place-"), eq("node-a"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token")))
                .thenReturn(new JobCreationResult(new JobBuilder().build(), client, "node-a", "/data/test.npz"));
        when(jobs.createDataProcessingJob(startsWith("centralized-"), eq("node-a"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("node-a"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"))).thenThrow(new IllegalStateException("unit test stop"));
        K8sTaskOrchestratorService service = new K8sTaskOrchestratorService(
                mock(DataManagementMapper.class), nodes, tasks, mock(MigrationTaskMapper.class),
                jobs, datasets, images, new ObjectMapper(), "node-a", "", Runnable::run,
                availability, mock(SchedulingPlanMapper.class), mock(DatasetUploadClient.class),
                authorization, placement(nodes, availability), heat);

        service.executeRegisteredTask(30, Collections.singletonList(10L), 7L, null, "COMPARISON");

        verify(tasks).updateTask(argThat(row -> "部分完成".equals(row.getStatus())));
        verify(heat).recordAccess(10L);
        verifyNoMoreInteractions(heat);
    }

    @Test
    void comparisonSumsPerDatasetMovementTimeAndComputesRating() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        K8sTaskOrchestratorService service = aggregationService(datasets, tasks);
        // Parallel IN_PLACE windows overlap (wall-clock span 1.5s) but the movement sum is 2.0s.
        DataItemResult inPlaceMnist = moved(1500L, "node-b", "node-b",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:01.500Z");
        DataItemResult inPlaceCifar = moved(500L, "node-c", "node-c",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:00.500Z");
        DataItemResult centralMnist = moved(3000L, "node-b", "node-a",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:03Z");
        DataItemResult centralCifar = moved(5000L, "node-c", "node-a",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:05Z");

        ReflectionTestUtils.invokeMethod(service, "updateComparisonTaskStatus", 30,
                Arrays.asList(10L, 11L), Arrays.asList(inPlaceMnist, inPlaceCifar),
                Arrays.asList(centralMnist, centralCifar));

        ArgumentCaptor<TaskManagement> row = ArgumentCaptor.forClass(TaskManagement.class);
        verify(tasks).updateTask(row.capture());
        assertEquals(30, row.getValue().getTaskId());
        assertEquals("已完成", row.getValue().getStatus());
        assertEquals(2.0, row.getValue().getT1());
        assertEquals(8.0, row.getValue().getT2());
        assertEquals(4.0, row.getValue().getRating());
        assertEquals(Boolean.TRUE, row.getValue().getExecutionEvidenceComplete());
        assertNotNull(row.getValue().getFinishedAt());
        assertNull(row.getValue().getDataPreparationMs());
        assertEquals("分布式调度方案:\n"
                + "mnist: node-b -> node-b\n"
                + "cifar: node-c -> node-c\n"
                + "中心化调度方案:\n"
                + "mnist: node-b -> node-a\n"
                + "cifar: node-c -> node-a", row.getValue().getSchedule());
        verify(tasks, never()).updateExecutionSummary(any());
    }

    @Test
    void comparisonWithAFailedJobIsPartialAndHasNoComparableTimes() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        K8sTaskOrchestratorService service = aggregationService(datasets, tasks);
        DataItemResult inPlaceMnist = moved(1500L, "node-b", "node-b",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:01.500Z");
        DataItemResult inPlaceCifar = moved(500L, "node-c", "node-c",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:00.500Z");
        DataItemResult centralMnist = moved(3000L, "node-b", "node-a",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:03Z");

        ReflectionTestUtils.invokeMethod(service, "updateComparisonTaskStatus", 30,
                Arrays.asList(10L, 11L), Arrays.asList(inPlaceMnist, inPlaceCifar),
                Arrays.asList(centralMnist, null));

        ArgumentCaptor<TaskManagement> row = ArgumentCaptor.forClass(TaskManagement.class);
        verify(tasks).updateTask(row.capture());
        assertEquals("部分完成", row.getValue().getStatus());
        assertNull(row.getValue().getT1());
        assertNull(row.getValue().getT2());
        assertNull(row.getValue().getRating());
        assertEquals(Boolean.FALSE, row.getValue().getExecutionEvidenceComplete());
        assertEquals("分布式调度方案:\n"
                + "mnist: node-b -> node-b\n"
                + "cifar: node-c -> node-c\n"
                + "中心化调度方案:\n"
                + "mnist: node-b -> node-a\n"
                + "cifar: 执行失败", row.getValue().getSchedule());
    }

    @Test
    void comparisonWithIncompleteEvidenceIsPartialAndHasNoComparableTimes() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        K8sTaskOrchestratorService service = aggregationService(datasets, tasks);
        DataItemResult inPlace = moved(1500L, "node-b", "node-b",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:01.500Z");
        DataItemResult central = moved(3000L, "node-b", "node-a",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:03Z");
        central.setOutputChecksumSha256(null);

        ReflectionTestUtils.invokeMethod(service, "updateComparisonTaskStatus", 30,
                Collections.singletonList(10L), Collections.singletonList(inPlace),
                Collections.singletonList(central));

        verify(tasks).updateTask(argThat(row -> "部分完成".equals(row.getStatus())
                && row.getT1() == null && row.getT2() == null && row.getRating() == null
                && Boolean.FALSE.equals(row.getExecutionEvidenceComplete())));
    }

    @Test
    void comparisonWithEveryJobFailedIsFailed() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        K8sTaskOrchestratorService service = aggregationService(datasets, tasks);

        ReflectionTestUtils.invokeMethod(service, "updateComparisonTaskStatus", 30,
                Collections.singletonList(10L), Collections.singletonList((DataItemResult) null),
                Collections.singletonList((DataItemResult) null));

        verify(tasks).updateTask(argThat(row -> "执行失败".equals(row.getStatus())
                && row.getT1() == null && row.getT2() == null && row.getRating() == null
                && "分布式调度方案:\nmnist: 执行失败\n中心化调度方案:\nmnist: 执行失败".equals(row.getSchedule())));
    }

    @Test
    void comparisonDoesNotProduceARatioForZeroDistributedMovementTime() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        K8sTaskOrchestratorService service = aggregationService(datasets, tasks);
        DataItemResult inPlace = moved(0L, "node-b", "node-b",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:00Z");
        DataItemResult central = moved(3000L, "node-b", "node-a",
                "2026-09-19T00:00:00Z", "2026-09-19T00:00:03Z");

        ReflectionTestUtils.invokeMethod(service, "updateComparisonTaskStatus", 30,
                Collections.singletonList(10L), Collections.singletonList(inPlace),
                Collections.singletonList(central));

        verify(tasks).updateTask(argThat(row -> "已完成".equals(row.getStatus())
                && Double.valueOf(0.0).equals(row.getT1()) && Double.valueOf(3.0).equals(row.getT2())
                && row.getRating() == null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inPlaceRunsOnTheNearestComputeNodeWhenTheReplicaSiteHasNone() {
        // cluster-hz-1 is a storage-only node and site 'hz' has no compute node, so the job
        // runs on the compute node nearest to it on the network topology.
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        RuntimeImageMapper images = mock(RuntimeImageMapper.class);
        K8sJobFactory jobs = mock(K8sJobFactory.class);
        DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
        DatasetAccessAuthorizationService authorization = mock(DatasetAccessAuthorizationService.class);
        NetworkTopologyService topology = mock(NetworkTopologyService.class);
        NodeManagement hz = NodeManagement.builder().nodeId(5).nodeName("cluster-hz-1")
                .type("storage").siteCode("hz").build();
        NodeManagement master40 = NodeManagement.builder().nodeId(1).nodeName("master-40")
                .type("compute-storage").siteCode("center").build();
        NodeManagement master215 = NodeManagement.builder().nodeId(3).nodeName("master-215")
                .type("compute").siteCode("center").build();
        when(nodes.getComputeCapableNodes()).thenReturn(Arrays.asList(master40, master215));
        when(nodes.getNodeById(5)).thenReturn(hz);
        when(nodes.getNodeByName("master-215")).thenReturn(master215);
        Map<Integer, NetworkTopologyService.NetworkPath> paths = new java.util.HashMap<>();
        paths.put(5, new NetworkTopologyService.NetworkPath(Collections.singletonList(5), 0.0, Long.MAX_VALUE));
        paths.put(1, new NetworkTopologyService.NetworkPath(Arrays.asList(5, 2, 1), 12.0, 100L));
        paths.put(3, new NetworkTopologyService.NetworkPath(Arrays.asList(5, 2, 3), 8.0, 100L));
        when(topology.pathsFrom(5)).thenReturn(paths);
        registerDataset(datasets, availability, 10L, "test", 5);
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
                .thenReturn("TRANSFER_MS=1000\nINPUT_BYTES=100\nINPUT_SHA256=" + SHA256);
        when(podResource.inContainer("processing-container").getLog()).thenReturn("result=ok");
        doReturn(new PodListBuilder().withItems(completedPod("master-215")).build()).when(pods).list();
        when(jobResource.get()).thenReturn(new JobBuilder().withNewStatus().addNewCondition()
                .withType("Complete").withStatus("True").endCondition().endStatus().build());
        when(jobs.createDataProcessingJob(startsWith("in-place-"), eq("cluster-hz-1"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("master-215"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token")))
                .thenReturn(new JobCreationResult(new JobBuilder().build(), client, "master-215", "/data/test.npz"));
        K8sTaskOrchestratorService service = new K8sTaskOrchestratorService(
                mock(DataManagementMapper.class), nodes, tasks, mock(MigrationTaskMapper.class),
                jobs, datasets, images, new ObjectMapper(), "master-40", "", Runnable::run,
                availability, mock(SchedulingPlanMapper.class), mock(DatasetUploadClient.class),
                authorization, placement(nodes, availability, topology), mock(DatasetHeatService.class));

        service.executeRegisteredTask(30, Collections.singletonList(10L), 7L, null, "IN_PLACE");

        verify(jobs).createDataProcessingJob(startsWith("in-place-"), eq("cluster-hz-1"), eq("test.npz"),
                eq("/dataset/test.npz"), eq("master-215"), isNull(), any(), any(), any(), same(image),
                eq("scoped-read-token"));
        verify(jobApi).create(any(Job.class));
        verify(tasks).updateExecutionSummary(argThat(summary -> "已完成".equals(summary.getStatus())
                && summary.getSchedule() != null
                && summary.getSchedule().contains("test: cluster-hz-1 -> master-215")
                && Boolean.TRUE.equals(summary.getExecutionEvidenceComplete())));
    }

    private static InPlacePlacementService placement(NodeManagementMapper nodes,
                                                     DatasetReplicaAvailabilityService availability) {
        return placement(nodes, availability, mock(NetworkTopologyService.class));
    }

    private static InPlacePlacementService placement(NodeManagementMapper nodes,
                                                     DatasetReplicaAvailabilityService availability,
                                                     NetworkTopologyService topology) {
        NodeAvailabilityService nodeAvailability = mock(NodeAvailabilityService.class);
        when(nodeAvailability.isSchedulable(any())).thenReturn(true);
        return new InPlacePlacementService(nodes, availability, nodeAvailability, topology);
    }

    private K8sTaskOrchestratorService aggregationService(DatasetRegistrationMapper datasets,
                                                          TaskManagementMapper tasks) {
        when(datasets.findDatasetById(10L)).thenReturn(RegisteredDataset.builder().datasetId(10L)
                .datasetCode("mnist").build());
        when(datasets.findDatasetById(11L)).thenReturn(RegisteredDataset.builder().datasetId(11L)
                .datasetCode("cifar").build());
        return new K8sTaskOrchestratorService(
                mock(DataManagementMapper.class), mock(NodeManagementMapper.class), tasks,
                mock(MigrationTaskMapper.class), mock(K8sJobFactory.class), datasets,
                mock(RuntimeImageMapper.class), new ObjectMapper(), "node-a", "", Runnable::run,
                mock(DatasetReplicaAvailabilityService.class), mock(SchedulingPlanMapper.class),
                mock(DatasetUploadClient.class),
                mock(DatasetAccessAuthorizationService.class), mock(InPlacePlacementService.class),
                mock(DatasetHeatService.class));
    }

    private DataItemResult moved(long preparationMs, String source, String target,
                                 String preparationStart, String preparationReady) {
        DataItemResult result = evidence(preparationStart, preparationReady,
                preparationReady, "2026-09-19T00:01:00Z");
        result.setPreparationMs(preparationMs);
        result.setT1Seconds(preparationMs / 1000.0);
        result.setSourceNodeName(source);
        result.setTargetNodeName(target);
        result.setActualNodeName(target);
        return result;
    }

    private void registerDataset(DatasetRegistrationMapper datasets,
                                 DatasetReplicaAvailabilityService availability,
                                 long datasetId, String code, int nodeId) {
        DatasetReplica replica = DatasetReplica.builder().replicaId(datasetId + 100).datasetId(datasetId)
                .nodeId(nodeId).filePath("/dataset/" + code + ".npz").sizeBytes(100L)
                .checksumAlgorithm("SHA-256").checksum(SHA256).build();
        when(datasets.findDatasetById(datasetId)).thenReturn(RegisteredDataset.builder().datasetId(datasetId)
                .datasetCode(code).datasetVersion("v1").status("ACTIVE").defaultRuntimeImageId(7L).build());
        when(datasets.listReplicas(datasetId)).thenReturn(Collections.singletonList(replica));
        when(availability.evaluate(replica)).thenReturn(new ReplicaAvailability("USABLE", true, null));
    }

    @SuppressWarnings("unchecked")
    private KubernetesClient completedJobClient(String nodeName) {
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
                .thenReturn("TRANSFER_MS=1000\nINPUT_BYTES=100\nINPUT_SHA256=" + SHA256);
        when(podResource.inContainer("processing-container").getLog()).thenReturn("result=ok");
        doReturn(new PodListBuilder().withItems(completedPod(nodeName)).build()).when(pods).list();
        when(jobResource.get()).thenReturn(new JobBuilder().withNewStatus().addNewCondition()
                .withType("Complete").withStatus("True").endCondition().endStatus().build());
        return client;
    }

    private Pod completedPod(String nodeName) {
        return new PodBuilder().withNewMetadata().withName("task-pod").endMetadata()
                .withNewSpec().withNodeName(nodeName).endSpec()
                .withNewStatus()
                .addNewInitContainerStatus().withName("data-transfer-container")
                .withNewState().withNewTerminated().withExitCode(0)
                .withStartedAt("2026-09-19T00:00:00Z").withFinishedAt("2026-09-19T00:00:01Z")
                .endTerminated().endState().endInitContainerStatus()
                .addNewContainerStatus().withName("processing-container")
                .withNewState().withNewTerminated().withExitCode(0)
                .withStartedAt("2026-09-19T00:00:01Z").withFinishedAt("2026-09-19T00:00:03Z")
                .endTerminated().endState().endContainerStatus().endStatus().build();
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
