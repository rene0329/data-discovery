package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.RuntimeImage;
import org.example.entity.SchedulingAssignment;
import org.example.entity.TaskManagement;
import org.example.factory.K8sJobFactory;
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

    @BeforeEach
    void setUp() {
        datasets = mock(DatasetRegistrationMapper.class);
        nodes = mock(NodeManagementMapper.class);
        tasks = mock(TaskManagementMapper.class);
        images = mock(RuntimeImageMapper.class);
        uploads = mock(DatasetUploadClient.class);
        jobs = mock(K8sJobFactory.class);
        service = new K8sTaskOrchestratorService(mock(DataManagementMapper.class), nodes, tasks,
                mock(MigrationTaskMapper.class), jobs, datasets, images, new ObjectMapper(), "", "", Runnable::run,
                mock(DatasetReplicaAvailabilityService.class), mock(SchedulingPlanMapper.class), uploads,
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

    private SchedulingAssignment assignment(String action) {
        return SchedulingAssignment.builder().datasetId(10L).replicaId(20L).sourceNodeId(3)
                .targetNodeId("USE_IN_PLACE".equals(action) ? 3 : 4).action(action).build();
    }
}
