package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.scheduling.SchedulableDatasetView;
import org.example.dto.scheduling.SchedulingPageResult;
import org.example.dto.scheduling.SchedulingPlanAccepted;
import org.example.dto.scheduling.SchedulingPlanRequest;
import org.example.entity.DatasetMetadata;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.example.mapper.TaskManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchedulingServiceTest {
    private DatasetRegistrationMapper datasetMapper;
    private NodeManagementMapper nodeMapper;
    private SchedulingPlanMapper planMapper;
    private TaskManagementMapper taskMapper;
    private DatasetReplicaAvailabilityService replicaAvailabilityService;
    private NodeAvailabilityService nodeAvailabilityService;
    private K8sTaskOrchestratorService orchestrator;
    private SchedulingService service;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(DatasetRegistrationMapper.class);
        nodeMapper = mock(NodeManagementMapper.class);
        planMapper = mock(SchedulingPlanMapper.class);
        taskMapper = mock(TaskManagementMapper.class);
        replicaAvailabilityService = mock(DatasetReplicaAvailabilityService.class);
        nodeAvailabilityService = mock(NodeAvailabilityService.class);
        orchestrator = mock(K8sTaskOrchestratorService.class);
        service = new SchedulingService(datasetMapper, nodeMapper, planMapper, taskMapper,
                replicaAvailabilityService, nodeAvailabilityService, orchestrator, new ObjectMapper());
    }

    @Test
    void listsActiveDatasetWithMetadataAndUsableReplica() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(10L).datasetCode("mnist").name("MNIST").datasetVersion("1.0")
                .status("ACTIVE").category("IMAGE").dataFormat("NPZ").build();
        DatasetReplica replica = DatasetReplica.builder()
                .replicaId(20L).datasetId(10L).nodeId(3).filePath("/dataset/mnist-1.0.npz")
                .sizeBytes(123L).availability("AVAILABLE").build();
        DatasetMetadata metadata = DatasetMetadata.builder().datasetId(10L)
                .profileJson("{\"sizeBytes\":123,\"sampleCount\":10}")
                .schemaJson("{\"type\":\"TENSOR\",\"tensors\":[{\"role\":\"FEATURE\",\"sampleShape\":[28,28,1]}]}")
                .schedulingHintsJson("{\"accessMode\":\"READ_ONLY\"}")
                .labelsJson("{\"task\":\"image-classification\"}").build();
        when(datasetMapper.listDatasets(null, "ACTIVE")).thenReturn(Collections.singletonList(dataset));
        when(datasetMapper.findDatasetMetadata(10L)).thenReturn(metadata);
        when(datasetMapper.listReplicas(10L)).thenReturn(Collections.singletonList(replica));
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodeMapper.getNodeById(3)).thenReturn(NodeManagement.builder().nodeName("node-3").build());

        SchedulingPageResult<SchedulableDatasetView> result = service.listDatasets(
                "10", "IMAGE", "NPZ", null, "task:image-classification", 1, 20);

        assertEquals(1, result.getTotal());
        assertEquals(10L, result.getList().get(0).getSampleCount());
        assertEquals(1, result.getList().get(0).getReplicas().size());
        assertEquals(1, result.getList().get(0).getSchemaSummary().get("tensorCount"));
    }

    @Test
    void storesAndDispatchesSubmittedPlan() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(10L).name("MNIST").status("ACTIVE").build();
        DatasetReplica replica = DatasetReplica.builder()
                .replicaId(20L).datasetId(10L).nodeId(3).availability("AVAILABLE").build();
        when(datasetMapper.findDatasetById(10L)).thenReturn(dataset);
        when(datasetMapper.findReplicaById(20L)).thenReturn(replica);
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodeMapper.getNodeById(3)).thenReturn(NodeManagement.builder().nodeId(3).build());
        when(nodeAvailabilityService.isSchedulable(any(NodeManagement.class))).thenReturn(true);
        doAnswer(invocation -> {
            org.example.entity.TaskManagement task = invocation.getArgument(0);
            task.setTaskId(30);
            return 1;
        }).when(taskMapper).submitData(any());
        doAnswer(invocation -> {
            org.example.entity.SchedulingPlan plan = invocation.getArgument(0);
            plan.setPlanId(40L);
            return 1;
        }).when(planMapper).insertPlan(any());
        doAnswer(invocation -> {
            org.example.entity.SchedulingAssignment assignment = invocation.getArgument(0);
            assignment.setAssignmentId(50L);
            return 1;
        }).when(planMapper).insertAssignment(any());

        SchedulingPlanRequest request = new SchedulingPlanRequest();
        request.setExternalPlanId("plan-1");
        request.setTaskId("task-1");
        SchedulingPlanRequest.Assignment item = new SchedulingPlanRequest.Assignment();
        item.setDatasetId(10L);
        item.setReplicaId(20L);
        item.setSourceNodeId(3);
        item.setTargetNodeId(3);
        item.setAction("USE_IN_PLACE");
        request.setAssignments(Collections.singletonList(item));

        SchedulingPlanAccepted accepted = service.submit(request);

        assertEquals(40L, accepted.getPlanId());
        assertEquals("ACCEPTED", accepted.getStatus());
        assertNotNull(accepted.getTaskId());
        verify(orchestrator).executeExternalPlan(any(), any(), any());
    }
}
