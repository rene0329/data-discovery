package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.scheduling.SchedulableDatasetView;
import org.example.dto.scheduling.SchedulingPageResult;
import org.example.dto.scheduling.SchedulingPlanAccepted;
import org.example.dto.scheduling.SchedulingPlanDetail;
import org.example.dto.scheduling.SchedulingPlanRequest;
import org.example.entity.DatasetMetadata;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.SchedulingAssignment;
import org.example.entity.SchedulingPlan;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.example.mapper.TaskManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private NetworkTopologyService topology;
    private DatasetSchedulingExecutor dataExecutor;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(DatasetRegistrationMapper.class);
        nodeMapper = mock(NodeManagementMapper.class);
        planMapper = mock(SchedulingPlanMapper.class);
        taskMapper = mock(TaskManagementMapper.class);
        replicaAvailabilityService = mock(DatasetReplicaAvailabilityService.class);
        nodeAvailabilityService = mock(NodeAvailabilityService.class);
        orchestrator = mock(K8sTaskOrchestratorService.class);
        topology = mock(NetworkTopologyService.class);
        dataExecutor = mock(DatasetSchedulingExecutor.class);
        service = new SchedulingService(datasetMapper, nodeMapper, planMapper, taskMapper,
                replicaAvailabilityService, nodeAvailabilityService, orchestrator, new ObjectMapper(), topology, dataExecutor,
                mock(DatasetHeatService.class));
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
    void listsExternalPlanExecutionRecordsWithFiltersAndPagination() {
        SchedulingPlan plan = SchedulingPlan.builder().planId(40L)
                .externalPlanId("plan-1").status("FAILED").errorMessage("copy failed").build();
        when(planMapper.countPlans("plan", "FAILED")).thenReturn(11L);
        when(planMapper.listPlans("plan", "FAILED", 10L, 10)).thenReturn(Collections.singletonList(plan));

        SchedulingPageResult<SchedulingPlan> result = service.listPlans(" plan ", " failed ", 2, 10);

        assertEquals(11L, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals("copy failed", result.getList().get(0).getErrorMessage());
        verifyNoInteractions(taskMapper, orchestrator);
    }

    @Test
    void validatesPlanFiltersAndHandlesPagesBeyondTheEnd() {
        assertThrows(RegistrationException.class, () -> service.listPlans(null, null, 0, 20));
        assertThrows(RegistrationException.class, () -> service.listPlans(null, null, 1, 101));
        assertThrows(RegistrationException.class, () -> service.listPlans(null, "UNKNOWN", 1, 20));
        verifyNoInteractions(planMapper);
        when(planMapper.countPlans(null, null)).thenReturn(1L);
        assertTrue(service.listPlans(null, null, Integer.MAX_VALUE, 100).getList().isEmpty());
    }

    @Test
    void returnsPlanAndItsIndividualExecutionResults() {
        SchedulingPlan plan = SchedulingPlan.builder().planId(40L).status("PARTIAL_COMPLETED").build();
        SchedulingAssignment assignment = SchedulingAssignment.builder().assignmentId(50L).planId(40L)
                .datasetId(10L).replicaId(20L).sourceNodeId(3).targetNodeId(4)
                .action("COPY_AND_USE").status("FAILED").errorMessage("copy failed").build();
        when(planMapper.findById(40L)).thenReturn(plan);
        when(planMapper.listAssignments(40L)).thenReturn(Collections.singletonList(assignment));

        SchedulingPlanDetail result = service.getPlan(40L);

        assertEquals("PARTIAL_COMPLETED", result.getPlan().getStatus());
        assertEquals("copy failed", result.getAssignments().get(0).getErrorMessage());
        assertEquals(4, result.getAssignments().get(0).getTargetNodeId());
        verifyNoInteractions(taskMapper, orchestrator);
        RegistrationException missing = assertThrows(RegistrationException.class, () -> service.getPlan(99L));
        assertEquals(404, missing.getStatus().value());
    }

    @Test
    void rejectsExternalAssignmentWithoutLogicalPathBeforeWritingPlan() {
        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(10L).status("ACTIVE").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(20L).datasetId(10L).nodeId(3).build();
        when(datasetMapper.findDatasetById(10L)).thenReturn(dataset);
        when(datasetMapper.findReplicaById(20L)).thenReturn(replica);
        when(replicaAvailabilityService.evaluate(replica)).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodeMapper.getNodeById(4)).thenReturn(NodeManagement.builder().nodeId(4).build());
        when(nodeAvailabilityService.isSchedulable(any(NodeManagement.class))).thenReturn(true);
        when(topology.requirePath(3, 4)).thenThrow(RegistrationException.conflict("No available logical topology path"));
        SchedulingPlanRequest request = new SchedulingPlanRequest();
        request.setExternalPlanId("plan-no-route");
        request.setTaskId("task-no-route");
        SchedulingPlanRequest.Assignment item = new SchedulingPlanRequest.Assignment();
        item.setDatasetId(10L); item.setReplicaId(20L);
        item.setSourceNodeId(3); item.setTargetNodeId(4); item.setAction("COPY_AND_USE");
        request.setAssignments(Collections.singletonList(item));

        assertThrows(RegistrationException.class, () -> service.submit(request));
        verify(planMapper, org.mockito.Mockito.never()).insertPlan(any());
        verifyNoInteractions(taskMapper, orchestrator);
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
        verify(topology).requirePath(3, 3);
        verify(orchestrator).executeExternalPlan(any(), any(), any());
        verifyNoInteractions(dataExecutor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COPY", "MOVE"})
    void dataPlansDoNotRequireAnImageOrCreateComputeTasks(String action) {
        SchedulingPlanRequest request = dataPlan(action);
        SchedulingPlanAccepted accepted = service.submitDataPlan(request);

        assertEquals(40L, accepted.getPlanId());
        ArgumentCaptor<SchedulingPlan> saved = ArgumentCaptor.forClass(SchedulingPlan.class);
        verify(planMapper).insertPlan(saved.capture());
        assertNull(saved.getValue().getInternalTaskId());
        verify(dataExecutor).execute(any(), any());
        verifyNoInteractions(taskMapper, orchestrator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USE_IN_PLACE", "REMOTE_READ", "COPY_AND_USE", "MOVE_AND_USE"})
    void dataEndpointRejectsComputeActions(String action) {
        SchedulingPlanRequest request = dataPlan(action);
        assertThrows(RegistrationException.class, () -> service.submitDataPlan(request));
        verifyNoInteractions(planMapper, dataExecutor, taskMapper, orchestrator);
    }

    @Test
    void legacyEndpointRejectsDataOnlyActions() {
        SchedulingPlanRequest request = dataPlan("COPY");
        request.setTaskId("compute-1");
        assertThrows(RegistrationException.class, () -> service.submit(request));
        verifyNoInteractions(planMapper, dataExecutor, taskMapper, orchestrator);
    }

    @Test
    void dataTransferRequiresADifferentStorageNode() {
        SchedulingPlanRequest request = dataPlan("MOVE");
        when(nodeMapper.getNodeById(4)).thenReturn(NodeManagement.builder().nodeId(4).type("compute").build());
        assertThrows(RegistrationException.class, () -> service.submitDataPlan(request));
        request.getAssignments().get(0).setTargetNodeId(3);
        when(nodeMapper.getNodeById(3)).thenReturn(NodeManagement.builder().nodeId(3).type("storage").build());
        assertThrows(RegistrationException.class, () -> service.submitDataPlan(request));
        verifyNoInteractions(dataExecutor, taskMapper, orchestrator);
    }

    @Test
    void retriedDataPlanIsNotDispatchedAgain() {
        SchedulingPlanRequest request = dataPlan("COPY");
        when(planMapper.findByExternalPlanId("manual-1")).thenReturn(SchedulingPlan.builder()
                .planId(40L).externalPlanId("manual-1").status("COMPLETED").build());
        assertEquals("COMPLETED", service.submitDataPlan(request).getStatus());
        verifyNoInteractions(dataExecutor, taskMapper, orchestrator);
    }

    private SchedulingPlanRequest dataPlan(String action) {
        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(10L).status("ACTIVE").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(20L).datasetId(10L).nodeId(3).build();
        when(datasetMapper.findDatasetById(10L)).thenReturn(dataset);
        when(datasetMapper.findReplicaById(20L)).thenReturn(replica);
        when(replicaAvailabilityService.evaluate(replica)).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodeMapper.getNodeById(4)).thenReturn(NodeManagement.builder().nodeId(4).type("storage").build());
        when(nodeAvailabilityService.isSchedulable(any())).thenReturn(true);
        doAnswer(invocation -> {
            ((SchedulingPlan) invocation.getArgument(0)).setPlanId(40L);
            return 1;
        }).when(planMapper).insertPlan(any());
        SchedulingPlanRequest request = new SchedulingPlanRequest();
        request.setExternalPlanId("manual-1");
        SchedulingPlanRequest.Assignment item = new SchedulingPlanRequest.Assignment();
        item.setDatasetId(10L); item.setReplicaId(20L);
        item.setSourceNodeId(3); item.setTargetNodeId(4); item.setAction(action);
        request.setAssignments(Collections.singletonList(item));
        return request;
    }
}
