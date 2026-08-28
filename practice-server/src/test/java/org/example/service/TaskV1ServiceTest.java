package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.CreateTaskRequest;
import org.example.dto.registration.TaskCreated;
import org.example.entity.RegisteredDataset;
import org.example.entity.RuntimeImage;
import org.example.entity.TaskManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.example.mapper.TaskManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskV1ServiceTest {
    private DatasetRegistrationMapper datasetMapper;
    private RuntimeImageMapper imageMapper;
    private TaskManagementMapper taskMapper;
    private K8sTaskOrchestratorService orchestrator;
    private TaskV1Service service;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(DatasetRegistrationMapper.class);
        imageMapper = mock(RuntimeImageMapper.class);
        taskMapper = mock(TaskManagementMapper.class);
        orchestrator = mock(K8sTaskOrchestratorService.class);
        service = new TaskV1Service(datasetMapper, imageMapper, taskMapper,
                mock(RegistrationAuditMapper.class), orchestrator, new ObjectMapper());
    }

    @Test
    void createAcceptsOnlyActiveDatasetAndUsableImage() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(11L).name("sales.csv").status("ACTIVE").build();
        RuntimeImage image = RuntimeImage.builder()
                .runtimeImageId(3L).status("READY").enabled(true).resolvedDigest("sha256:abc").build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(dataset);
        when(datasetMapper.countAvailableReplicas(11L)).thenReturn(1);
        when(imageMapper.findById(3L)).thenReturn(image);
        doAnswer(invocation -> {
            invocation.<TaskManagement>getArgument(0).setTaskId(42);
            return null;
        }).when(taskMapper).submitData(any(TaskManagement.class));

        CreateTaskRequest request = request();
        TaskCreated created = service.create(request, "request-3");

        assertEquals(42, created.getTaskId());
        assertEquals("ACCEPTED", created.getStatus());
        verify(orchestrator).executeRegisteredTask(eq(42), eq(Collections.singletonList(11L)), eq(3L), eq(null));
    }

    @Test
    void createRejectsInactiveDataset() {
        when(datasetMapper.findDatasetById(11L)).thenReturn(
                RegisteredDataset.builder().datasetId(11L).status("DRAFT").build());

        assertThrows(RegistrationException.class, () -> service.create(request(), "request-4"));
    }

    private CreateTaskRequest request() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("registered task");
        request.setDatasetIds(Collections.singletonList(11L));
        request.setRuntimeImageId(3L);
        return request;
    }
}
