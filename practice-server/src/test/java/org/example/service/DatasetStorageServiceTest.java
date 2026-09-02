package org.example.service;

import org.example.dto.scheduling.DatasetStoragePlan;
import org.example.dto.scheduling.SchedulingPlanRequest;
import org.example.entity.*;
import org.example.exception.RegistrationException;
import org.example.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatasetStorageServiceTest {
    DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
    NodeManagementMapper nodes = mock(NodeManagementMapper.class);
    TaskManagementMapper tasks = mock(TaskManagementMapper.class);
    SchedulingService scheduling = mock(SchedulingService.class);
    NetworkTopologyService topology = mock(NetworkTopologyService.class);
    DatasetStorageService service;
    List<RegisteredDataset> catalog;

    @BeforeEach
    void setup() {
        NodeAvailabilityService availability = new NodeAvailabilityService(300);
        service = new DatasetStorageService(datasets, nodes, tasks, availability,
                new DatasetReplicaAvailabilityService(nodes, availability), topology, scheduling);
        List<NodeManagement> pool = Arrays.asList(node(1, "storage"), node(2, "compute-storage"), node(3, "storage"));
        when(nodes.selectAllNodes()).thenReturn(pool);
        pool.forEach(n -> when(nodes.getNodeById(n.getNodeId())).thenReturn(n));
        catalog = Arrays.asList(dataset(9, 80), dataset(10, 10));
        when(datasets.listDatasets(null, null)).thenReturn(catalog);
        for (RegisteredDataset dataset : catalog) {
            when(datasets.listReplicas(dataset.getDatasetId())).thenReturn(Collections.singletonList(
                    DatasetReplica.builder().replicaId(dataset.getDatasetId() + 100).datasetId(dataset.getDatasetId())
                            .nodeId(1).filePath("/dataset/" + dataset.getDatasetId() + ".npz").availability("AVAILABLE").build()));
        }
        Map<Integer, NetworkTopologyService.NetworkPath> paths = new HashMap<>();
        pool.forEach(n -> paths.put(n.getNodeId(), new NetworkTopologyService.NetworkPath(Arrays.asList(1, n.getNodeId()), 1, 100)));
        when(topology.pathsFrom(1)).thenReturn(paths);
    }

    @Test
    void previewsLogicalHeatAndCreatesBackupBeforeMovingWithoutWritingAnything() {
        DatasetStoragePlan plan = service.preview("heat");
        assertEquals(2, plan.getDatasetCount());
        assertEquals(9L, plan.getPlacements().get(0).getDatasetId());
        assertEquals(80.0, plan.getPlacements().get(0).getDataHeat());
        assertEquals("COPY", plan.getAssignments().get(0).getAction());
        assertEquals("MOVE", plan.getAssignments().get(1).getAction());
        assertEquals(109L, plan.getAssignments().get(1).getReplicaId());
        assertNotEquals(plan.getAssignments().get(0).getTargetNodeId(), plan.getAssignments().get(1).getTargetNodeId());
        verifyNoInteractions(scheduling);
    }

    @Test
    void taskConditionsApplyToBothPreviewAndSubmission() {
        assertThrows(RegistrationException.class, () -> service.preview("aggregation"));
        when(tasks.countTasks()).thenReturn(1);
        assertEquals(false, service.policy().get("heatEnabled"));
        assertThrows(RegistrationException.class, () -> service.preview("heat"));
        assertFalse(service.preview("aggregation").getAssignments().isEmpty());
        assertThrows(RegistrationException.class, () -> service.preview("invalid"));
    }

    @Test
    void submitsReviewedAssignmentsToDataOnlySchedulingAndRejectsChangedPreview() {
        DatasetStoragePlan.Submit request = new DatasetStoragePlan.Submit();
        request.setExternalPlanId("storage-test-001");
        request.setMode("heat");
        request.setAssignments(service.preview("heat").getAssignments());
        service.submit(request);
        org.mockito.ArgumentCaptor<SchedulingPlanRequest> captor = org.mockito.ArgumentCaptor.forClass(SchedulingPlanRequest.class);
        verify(scheduling).submitDataPlan(captor.capture());
        assertEquals("热敏存储", captor.getValue().getAlgorithm().getName());
        assertNull(captor.getValue().getTaskId());
        when(topology.pathsFrom(1)).thenReturn(Collections.emptyMap());
        assertThrows(RegistrationException.class, () -> service.submit(request));
        verify(scheduling, times(1)).submitDataPlan(any());
    }

    @Test
    void unavailableOrBusyDatasetsAreSkippedWithoutMovingUnrelatedReplicas() {
        catalog.get(0).setStatus("DISABLED");
        when(datasets.countActiveSchedulingReferences(10L)).thenReturn(1);
        DatasetStoragePlan plan = service.preview("heat");
        assertEquals(1, plan.getDatasetCount());
        assertTrue(plan.getAssignments().isEmpty());
        assertTrue(plan.getNotices().get(0).contains("未完成"));
    }

    @Test
    void existingReplicasAndFullTargetsDoNotCauseDuplicateCopies() {
        when(datasets.listReplicas(9L)).thenReturn(Arrays.asList(
                DatasetReplica.builder().datasetId(9L).replicaId(109L).nodeId(1).availability("AVAILABLE").filePath("/dataset/9.npz").build(),
                DatasetReplica.builder().datasetId(9L).replicaId(200L).nodeId(2).availability("AVAILABLE").filePath("/dataset/9.npz").build()));
        nodes.selectAllNodes().get(2).setNumDataset(0);
        DatasetStoragePlan plan = service.preview("heat");
        assertTrue(plan.getAssignments().stream().noneMatch(a -> a.getDatasetId().equals(9L)));
    }

    private RegisteredDataset dataset(long id, double heat) {
        return RegisteredDataset.builder().datasetId(id).name("same-name").status("ACTIVE").dataHeat(heat).build();
    }
    private NodeManagement node(int id, String type) {
        return NodeManagement.builder().nodeId(id).nodeName("node-" + id).type(type).numDataset(10)
                .enabled(true).registrationStatus("ACTIVE").observedStatus("ONLINE")
                .lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }
}
