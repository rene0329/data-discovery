package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.CreateTaskRequest;
import org.example.dto.registration.TaskCreated;
import org.example.entity.RegisteredDataset;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RuntimeImage;
import org.example.entity.TaskManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.mapper.NodeManagementMapper;
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
    private DatasetReplicaAvailabilityService replicaAvailabilityService;
    private NodeAvailabilityService nodeAvailabilityService;
    private NodeManagementMapper nodeMapper;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(DatasetRegistrationMapper.class);
        imageMapper = mock(RuntimeImageMapper.class);
        taskMapper = mock(TaskManagementMapper.class);
        orchestrator = mock(K8sTaskOrchestratorService.class);
        replicaAvailabilityService = mock(DatasetReplicaAvailabilityService.class);
        nodeAvailabilityService = mock(NodeAvailabilityService.class);
        nodeMapper = mock(NodeManagementMapper.class);
        service = new TaskV1Service(datasetMapper, imageMapper, taskMapper,
                mock(RegistrationAuditMapper.class), orchestrator, new ObjectMapper(), nodeMapper,
                replicaAvailabilityService, nodeAvailabilityService, "compute");
    }

    @Test
    void createAcceptsOnlyActiveDatasetAndUsableImage() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(11L).name("sales.csv").status("ACTIVE").build();
        RuntimeImage image = RuntimeImage.builder()
                .runtimeImageId(3L).status("READY").enabled(true).resolvedDigest("sha256:abc").build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(dataset);
        DatasetReplica replica = DatasetReplica.builder().replicaId(1L).nodeId(7).availability("AVAILABLE").build();
        when(datasetMapper.listReplicas(11L)).thenReturn(Collections.singletonList(replica));
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(imageMapper.findById(3L)).thenReturn(image);
        NodeManagement compute = NodeManagement.builder().nodeId(3).build();
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Collections.singletonList(compute));
        when(nodeAvailabilityService.isSchedulable(compute)).thenReturn(true);
        when(nodeMapper.getNodeByName("compute")).thenReturn(compute);
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

    @Test
    void preflightRejectsReplicaOnUnavailableNodeBeforeTaskIsAccepted() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(11L).name("catdog").status("ACTIVE").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(1L).nodeId(3)
                .availability("AVAILABLE").build();
        RuntimeImage image = RuntimeImage.builder().runtimeImageId(3L).name("image")
                .status("READY").enabled(true).resolvedDigest("sha256:abc").build();
        NodeManagement compute = NodeManagement.builder().nodeId(2).build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(dataset);
        when(datasetMapper.listReplicas(11L)).thenReturn(Collections.singletonList(replica));
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("UNREACHABLE", false, "节点未启用"));
        when(imageMapper.findById(3L)).thenReturn(image);
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Collections.singletonList(compute));
        when(nodeAvailabilityService.isSchedulable(compute)).thenReturn(true);
        when(nodeMapper.getNodeByName("compute")).thenReturn(compute);

        RegistrationException exception = assertThrows(RegistrationException.class,
                () -> service.create(request(), "request-disabled-node"));

        assertEquals("DATASET_NO_USABLE_REPLICA", exception.getErrorCode());
    }

    private CreateTaskRequest request() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("registered task");
        request.setDatasetIds(Collections.singletonList(11L));
        request.setRuntimeImageId(3L);
        return request;
    }
}
