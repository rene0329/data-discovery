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
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.RuntimeImage;
import org.example.entity.SchedulingAssignment;
import org.example.entity.TaskManagement;
import org.example.factory.K8sJobFactory;
import org.example.factory.JobCreationResult;
import org.example.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalPlanRuntimeImageTest {
    private DatasetRegistrationMapper datasets;
    private NodeManagementMapper nodes;
    private TaskManagementMapper tasks;
    private RuntimeImageMapper images;
    private DatasetUploadClient uploads;
    private K8sJobFactory jobs;
    private K8sTaskOrchestratorService service;
    private RuntimeImage selectedImage;
    private SchedulingPlanMapper plans;

    @BeforeEach
    void setUp() {
        datasets = mock(DatasetRegistrationMapper.class);
        nodes = mock(NodeManagementMapper.class);
        tasks = mock(TaskManagementMapper.class);
        images = mock(RuntimeImageMapper.class);
        uploads = mock(DatasetUploadClient.class);
        jobs = mock(K8sJobFactory.class);
        plans = mock(SchedulingPlanMapper.class);
        service = new K8sTaskOrchestratorService(mock(DataManagementMapper.class), nodes, tasks,
                mock(MigrationTaskMapper.class), jobs, datasets, images, new ObjectMapper(), "", "", Runnable::run,
                mock(DatasetReplicaAvailabilityService.class), plans, uploads,
                mock(NetworkTopologyService.class));
        when(datasets.findDatasetById(10L)).thenReturn(RegisteredDataset.builder().datasetId(10L)
                .datasetCode("test").defaultRuntimeImageId(8L).build());
        when(datasets.findReplicaById(20L)).thenReturn(DatasetReplica.builder().replicaId(20L)
                .datasetId(10L).nodeId(3).filePath("/dataset/test.npz").sizeBytes(100L).build());
        when(nodes.getNodeById(3)).thenReturn(NodeManagement.builder().nodeId(3).nodeName("source")
                .type("compute-storage").build());
        when(nodes.getNodeById(4)).thenReturn(NodeManagement.builder().nodeId(4).nodeName("target")
                .type("compute-storage").build());
        when(tasks.getTaskByTaskId(30)).thenReturn(TaskManagement.builder().taskId(30).runtimeImageId(7L).build());
        selectedImage = RuntimeImage.builder().runtimeImageId(7L).status("READY").enabled(true)
                .resolvedDigest("sha256:selected").commandJson("[\"python\"]").argsTemplateJson("[\"run.py\"]").build();
        when(images.findById(7L)).thenReturn(selectedImage);
        // Stop at the job factory: this unit test must not submit to Kubernetes.
        when(jobs.createDataProcessingJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                isNull(), any(), any(), any(), any())).thenThrow(new IllegalStateException("unit test stop"));
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
                eq("/dataset/test.npz"), eq(executionNode), isNull(), any(), any(), any(), same(selectedImage));
        assertEquals("python", selectedImage.getCommand().get(0));
        verify(images, never()).findById(8L);
        if (inPlace) verifyNoInteractions(uploads);
        else verify(uploads).copyFrom(any(), any(), eq("/dataset/test.npz"), eq(100L));
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
        when(client.pods()).thenReturn(pods);
        when(client.batch().v1().jobs()).thenReturn(jobApi);
        doReturn(jobResource).when(jobApi).withName(anyString());
        doReturn(mock(PodResource.class, RETURNS_DEEP_STUBS)).when(pods).withName(anyString());
        doReturn(new PodListBuilder().withItems(new PodBuilder()
                        .withNewMetadata().withName("test-pod").endMetadata()
                        .withNewStatus().addNewInitContainerStatus().withName("data-transfer-container")
                        .withNewState().withNewTerminated().withExitCode(0)
                        .withStartedAt("2026-09-03T00:00:00Z").withFinishedAt("2026-09-03T00:00:01Z")
                        .endTerminated().endState().endInitContainerStatus().endStatus().build()).build()).when(pods).list();
        when(jobResource.get())
                .thenReturn(new JobBuilder().withNewStatus().addNewCondition()
                        .withType(jobCondition).withStatus("True").endCondition().endStatus().build());
        doReturn(new JobCreationResult(new JobBuilder().build(), client, "source"))
                .when(jobs).createDataProcessingJob(anyString(), anyString(), anyString(), anyString(), anyString(),
                        isNull(), any(), any(), any(), any());

        service.executeExternalPlan(40L, 30, java.util.Collections.singletonList(assignment("USE_IN_PLACE")));

        verify(plans).updatePlanStatus(eq(40L), eq("Complete".equals(jobCondition) ? "COMPLETED" : "FAILED"), any());
        verify(jobResource).get();
        verifyNoInteractions(uploads);
    }

    private SchedulingAssignment assignment(String action) {
        return SchedulingAssignment.builder().datasetId(10L).replicaId(20L).sourceNodeId(3)
                .targetNodeId("USE_IN_PLACE".equals(action) ? 3 : 4).action(action).build();
    }
}
