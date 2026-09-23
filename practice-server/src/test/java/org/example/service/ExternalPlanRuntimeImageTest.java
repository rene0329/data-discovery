package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import org.example.entity.DatasetReplica;
import org.example.entity.DatasetMetadata;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.RuntimeImage;
import org.example.entity.SchedulingAssignment;
import org.example.entity.TaskManagement;
import org.example.factory.K8sJobFactory;
import org.example.factory.JobCreationResult;
import org.example.mapper.*;
import org.example.model.FileIntegrityResult;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.DatasetAccessAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalPlanRuntimeImageTest {
    private static final String SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private DatasetRegistrationMapper datasets;
    private NodeManagementMapper nodes;
    private TaskManagementMapper tasks;
    private RuntimeImageMapper images;
    private DatasetUploadClient uploads;
    private K8sJobFactory jobs;
    private K8sTaskOrchestratorService service;
    private RuntimeImage selectedImage;
    private SchedulingPlanMapper plans;
    private DatasetAccessAuthorizationService authorization;

    @BeforeEach
    void setUp() {
        datasets = mock(DatasetRegistrationMapper.class);
        nodes = mock(NodeManagementMapper.class);
        tasks = mock(TaskManagementMapper.class);
        images = mock(RuntimeImageMapper.class);
        uploads = mock(DatasetUploadClient.class);
        jobs = mock(K8sJobFactory.class);
        plans = mock(SchedulingPlanMapper.class);
        authorization = mock(DatasetAccessAuthorizationService.class);
        service = new K8sTaskOrchestratorService(mock(DataManagementMapper.class), nodes, tasks,
                mock(MigrationTaskMapper.class), jobs, datasets, images, new ObjectMapper(), "", "", Runnable::run,
                mock(DatasetReplicaAvailabilityService.class), plans, uploads,
                authorization, mock(InPlacePlacementService.class));
        when(datasets.findDatasetById(10L)).thenReturn(RegisteredDataset.builder().datasetId(10L)
                .datasetCode("test").datasetVersion("v1").defaultRuntimeImageId(8L).build());
        when(datasets.findReplicaById(20L)).thenReturn(DatasetReplica.builder().replicaId(20L)
                .datasetId(10L).nodeId(3).filePath("/dataset/test.npz").sizeBytes(100L)
                .checksumAlgorithm("SHA-256").checksum(SHA256).build());
        when(datasets.findDatasetMetadata(10L)).thenReturn(DatasetMetadata.builder().datasetId(10L)
                .authoritativeSizeBytes(100L).digestAlgorithm("SHA-256").digestValue(SHA256).build());
        when(nodes.getNodeById(3)).thenReturn(NodeManagement.builder().nodeId(3).nodeName("source")
                .type("compute-storage").build());
        when(nodes.getNodeById(4)).thenReturn(NodeManagement.builder().nodeId(4).nodeName("target")
                .type("compute-storage").build());
        when(tasks.getTaskByTaskId(30)).thenReturn(TaskManagement.builder().taskId(30).runtimeImageId(7L)
                .acceptanceRunId("acceptance-run-1").build());
        selectedImage = RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true)
                .resolvedDigest("sha256:selected").commandJson("[\"python\"]").argsTemplateJson("[\"run.py\"]").build();
        when(images.findById(7L)).thenReturn(selectedImage);
        AccessAuthorizationResult grant = new AccessAuthorizationResult();
        grant.setToken("scoped-read-token");
        when(authorization.issueInternal(any(), any())).thenReturn(grant);
        when(uploads.copyFrom(any(), any(), eq("/dataset/test.npz"), eq(100L), eq(SHA256),
                eq(10L), eq("v1"), eq("task-30-assignment-50"), eq("acceptance-run-1")))
                .thenReturn(new FileIntegrityResult("/dataset/test.npz", 100L,
                        "SHA-256", SHA256, true, "verified"));
        // Stop at the job factory: this unit test must not submit to Kubernetes.
        when(jobs.createDataProcessingJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                isNull(), any(), any(), any(), any(), eq("scoped-read-token")))
                .thenThrow(new IllegalStateException("unit test stop"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USE_IN_PLACE", "COPY_AND_USE", "MOVE_AND_USE"})
    void passesExplicitImageAndChosenNodeToJobFactory(String action) {
        SchedulingAssignment assignment = assignment(action);
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "processExternalAssignment", 30, assignment));
        boolean inPlace = "USE_IN_PLACE".equals(action);
        String executionNode = inPlace ? "source" : "target";
        verify(jobs).createDataProcessingJob(anyString(), eq(executionNode), eq("test.npz"),
                eq("/dataset/test.npz"), eq(executionNode), isNull(), any(), any(), any(), same(selectedImage),
                eq("scoped-read-token"));
        assertEquals("python", selectedImage.getCommand().get(0));
        verify(images, never()).findById(8L);
        if (inPlace) verifyNoInteractions(uploads);
        else {
            verify(uploads).copyFrom(any(), any(), eq("/dataset/test.npz"), eq(100L), eq(SHA256),
                    eq(10L), eq("v1"), eq("task-30-assignment-50"), eq("acceptance-run-1"));
            verify(datasets).updateReplicaIntegrity(any(), eq(100L), eq("SHA-256"), eq(SHA256),
                    eq("AVAILABLE"), anyString(), eq(true));
            if ("MOVE_AND_USE".equals(action)) {
                verify(uploads).delete(any(), eq("/dataset/test.npz"), eq(10L), eq("v1"),
                        eq("task-30-assignment-50"), eq("acceptance-run-1"));
            } else {
                verify(uploads, never()).delete(any(), anyString(), any(), any(), any(), any());
            }
        }
    }

    @Test
    void rejectsCopiedReplicaThatDoesNotMatchDatasetAuthority() {
        when(uploads.copyFrom(any(), any(), eq("/dataset/test.npz"), eq(100L), eq(SHA256),
                eq(10L), eq("v1"), eq("task-30-assignment-50"), eq("acceptance-run-1")))
                .thenReturn(new FileIntegrityResult("/dataset/test.npz", 99L,
                        "SHA-256", SHA256, false, "size mismatch"));

        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "processExternalAssignment", 30, assignment("MOVE_AND_USE")));

        verify(datasets).updateReplicaIntegrity(any(), eq(99L), eq("SHA-256"), eq(SHA256),
                eq("VERIFY_FAILED"), anyString(), eq(false));
        verify(uploads, never()).scan(any());
        verify(uploads, never()).delete(any(), anyString(), any(), any(), any(), any());
        verifyNoInteractions(jobs);
    }

    @Test
    void rechecksImageBeforeMovingOrDeletingAnyData() {
        selectedImage.setEnabled(false);
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "processExternalAssignment", 30, assignment("MOVE_AND_USE")));
        verifyNoInteractions(uploads, jobs);
    }

    @Test
    void legacyPlanWithoutExplicitImageStillUsesDatasetDefault() {
        when(tasks.getTaskByTaskId(30)).thenReturn(TaskManagement.builder().taskId(30).build());
        when(images.findById(8L)).thenReturn(selectedImage);
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "processExternalAssignment", 30, assignment("USE_IN_PLACE")));
        verify(images).findById(8L);
        verify(images, never()).findById(7L);
        verifyNoInteractions(uploads);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Complete", "Failed"})
    @SuppressWarnings("unchecked")
    void computePlanReportsProcessingResultEvenWhenTransferSucceeded(String jobCondition) {
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
        doReturn(new PodListBuilder().withItems(new PodBuilder()
                        .withNewMetadata().withName("test-pod").endMetadata()
                        .withNewSpec().withNodeName("source").endSpec()
                        .withNewStatus().addNewInitContainerStatus().withName("data-transfer-container")
                        .withNewState().withNewTerminated().withExitCode(0)
                        .withStartedAt("2026-09-03T00:00:00Z").withFinishedAt("2026-09-03T00:00:01Z")
                        .endTerminated().endState().endInitContainerStatus()
                        .addNewContainerStatus().withName("processing-container")
                        .withNewState().withNewTerminated().withExitCode(0)
                        .withStartedAt("2026-09-03T00:00:01Z").withFinishedAt("2026-09-03T00:00:02Z")
                        .endTerminated().endState().endContainerStatus().endStatus().build()).build()).when(pods).list();
        when(jobResource.get())
                .thenReturn(new JobBuilder().withNewStatus().addNewCondition()
                        .withType(jobCondition).withStatus("True").endCondition().endStatus().build());
        doReturn(new JobCreationResult(new JobBuilder().build(), client, "source"))
                .when(jobs).createDataProcessingJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                        isNull(), any(), any(), any(), any(), eq("scoped-read-token"));

        service.executeExternalPlan(40L, 30, java.util.Collections.singletonList(assignment("USE_IN_PLACE")));

        verify(plans).updatePlanStatus(eq(40L), eq("Complete".equals(jobCondition) ? "COMPLETED" : "FAILED"), any());
        verify(jobResource).get();
        verifyNoInteractions(uploads);
    }

    @Test
    @SuppressWarnings("unchecked")
    void externalPlanScheduleIsGroupedByTargetNodeAndStaysOutOfPerformanceAnalysis() {
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
        doReturn(new PodListBuilder().withItems(new PodBuilder()
                        .withNewMetadata().withName("test-pod").endMetadata()
                        .withNewSpec().withNodeName("source").endSpec()
                        .withNewStatus().addNewInitContainerStatus().withName("data-transfer-container")
                        .withNewState().withNewTerminated().withExitCode(0)
                        .withStartedAt("2026-09-03T00:00:00Z").withFinishedAt("2026-09-03T00:00:01Z")
                        .endTerminated().endState().endInitContainerStatus()
                        .addNewContainerStatus().withName("processing-container")
                        .withNewState().withNewTerminated().withExitCode(0)
                        .withStartedAt("2026-09-03T00:00:01Z").withFinishedAt("2026-09-03T00:00:02Z")
                        .endTerminated().endState().endContainerStatus().endStatus().build()).build()).when(pods).list();
        when(jobResource.get()).thenReturn(new JobBuilder().withNewStatus().addNewCondition()
                .withType("Complete").withStatus("True").endCondition().endStatus().build());
        doAnswer(invocation -> new JobCreationResult(new JobBuilder().build(), client, invocation.getArgument(4)))
                .when(jobs).createDataProcessingJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                        isNull(), any(), any(), any(), any(), eq("scoped-read-token"));
        // A second dataset whose replica has disappeared: its assignment fails before any job.
        when(datasets.findDatasetById(11L)).thenReturn(RegisteredDataset.builder().datasetId(11L)
                .datasetCode("second").datasetVersion("v1").defaultRuntimeImageId(8L).build());
        SchedulingAssignment inPlace = assignment("USE_IN_PLACE");
        inPlace.setAssignmentId(60L);
        SchedulingAssignment copy = assignment("COPY_AND_USE");
        SchedulingAssignment failedMove = SchedulingAssignment.builder().planId(40L).assignmentId(70L)
                .datasetId(11L).replicaId(21L).sourceNodeId(3).targetNodeId(4).action("MOVE_AND_USE").build();

        service.executeExternalPlan(40L, 30, java.util.Arrays.asList(inPlace, copy, failedMove));

        verify(plans).updatePlanStatus(eq(40L), eq("PARTIAL_COMPLETED"), any());
        org.mockito.ArgumentCaptor<TaskManagement> row = org.mockito.ArgumentCaptor.forClass(TaskManagement.class);
        verify(tasks).updateTask(row.capture());
        assertEquals("调度目标节点 source:\n"
                + "test: source -> source [原位]\n"
                + "调度目标节点 target:\n"
                + "test: source -> target [复制]\n"
                + "second: 执行失败", row.getValue().getSchedule());
        assertEquals("部分完成", row.getValue().getStatus());
        assertEquals(2.0, row.getValue().getT1());
        assertNull(row.getValue().getT2());
        assertNull(row.getValue().getRating());
        assertFalse(row.getValue().getSchedule().contains("中心化调度方案:"));
    }

    @Test
    void externalActionLabelsDescribeTheActualDataMovement() {
        assertEquals("复制", K8sTaskOrchestratorService.externalActionLabel("COPY_AND_USE", false));
        assertEquals("迁移", K8sTaskOrchestratorService.externalActionLabel("MOVE_AND_USE", false));
        assertEquals("远程读取", K8sTaskOrchestratorService.externalActionLabel("REMOTE_READ", false));
        assertEquals("原位", K8sTaskOrchestratorService.externalActionLabel("USE_IN_PLACE", true));
        // COPY/MOVE onto the source node itself skips the transfer, so it runs in place.
        assertEquals("原位", K8sTaskOrchestratorService.externalActionLabel("COPY_AND_USE", true));
        assertEquals("原位", K8sTaskOrchestratorService.externalActionLabel("REMOTE_READ", true));
    }

    private SchedulingAssignment assignment(String action) {
        return SchedulingAssignment.builder().planId(40L).assignmentId(50L)
                .datasetId(10L).replicaId(20L).sourceNodeId(3)
                .targetNodeId("USE_IN_PLACE".equals(action) ? 3 : 4).action(action).build();
    }
}
