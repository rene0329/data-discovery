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
                new DatasetReplicaAvailabilityService(nodes, availability), topology, scheduling,
                mock(InPlacePlacementService.class), 30, 1);
        List<NodeManagement> pool = Arrays.asList(node(1, "storage"), node(2, "compute-storage"), node(3, "storage"));
        when(nodes.selectAllNodes()).thenReturn(pool);
        pool.forEach(n -> when(nodes.getNodeById(n.getNodeId())).thenReturn(n));
        catalog = Arrays.asList(dataset(9, 80), dataset(10, 10));
        when(datasets.listDatasets(null, null)).thenReturn(catalog);
        for (RegisteredDataset dataset : catalog) {
            when(datasets.findDatasetById(dataset.getDatasetId())).thenReturn(dataset);
            when(datasets.listReplicas(dataset.getDatasetId())).thenReturn(Collections.singletonList(
                    availableReplica(dataset.getDatasetId() + 100, dataset.getDatasetId(), 1,
                            "/dataset/" + dataset.getDatasetId() + ".npz")));
        }
        Map<Integer, NetworkTopologyService.NetworkPath> paths = new HashMap<>();
        pool.forEach(n -> paths.put(n.getNodeId(), new NetworkTopologyService.NetworkPath(Arrays.asList(1, n.getNodeId()), 1, 100)));
        pool.forEach(n -> when(topology.pathsFrom(n.getNodeId())).thenReturn(paths));
    }

    @Test
    void bothModesRemainAvailableWithAndWithoutUnfinishedTasks() {
        for (int count : new int[]{0, 1, 5}) {
            when(tasks.countUnfinishedTasks()).thenReturn(count);
            assertEquals(true, service.policy().get("heatEnabled"));
            assertEquals(true, service.policy().get("aggregationEnabled"));
            assertEquals(count, service.policy().get("unfinishedTaskCount"));
            assertFalse(service.preview("aggregation", Collections.singletonList(10L), 2).getAssignments().isEmpty());
        }
        assertThrows(RegistrationException.class, () -> service.preview("invalid"));
        assertThrows(RegistrationException.class, () -> service.preview("aggregation"));
    }

    @Test
    void aggregationCopiesOnlyRequestedDataToComputeTargetAndRetainsSource() {
        DatasetStoragePlan plan = service.preview("aggregation", Collections.singletonList(10L), 2);
        assertEquals(1, plan.getDatasetCount());
        assertEquals(1, plan.getAssignments().size());
        assertEquals(10L, plan.getAssignments().get(0).getDatasetId());
        assertEquals(2, plan.getAssignments().get(0).getTargetNodeId());
        assertEquals("COPY", plan.getAssignments().get(0).getAction());
        DatasetStoragePlan.Submit request = new DatasetStoragePlan.Submit();
        request.setMode("aggregation"); request.setDatasetIds(Collections.singletonList(10L));
        request.setTargetNodeId(2); request.setExternalPlanId("aggregation-selected");
        request.setAssignments(plan.getAssignments());
        service.submit(request);
        verify(scheduling).submitDataPlan(any());
    }

    @Test
    void aggregationReusesTargetAndSkipsBusyDatasets() {
        when(datasets.countActiveTaskReferences(9L, "same-name")).thenReturn(1);
        DatasetStoragePlan aggregation = service.preview("aggregation", Arrays.asList(9L, 10L), 2);
        assertEquals(1, aggregation.getAssignments().size());
        assertTrue(aggregation.getNotices().get(0).contains("占用") || aggregation.getNotices().get(0).contains("未完成"));
        when(datasets.listReplicas(10L)).thenReturn(Collections.singletonList(
                availableReplica(200L, 10L, 2, "/dataset/10.npz")));
        DatasetStoragePlan reuse = service.preview("aggregation", Collections.singletonList(10L), 2);
        assertTrue(reuse.getAssignments().isEmpty());
        assertTrue(reuse.getNotices().get(0).contains("直接复用"));
    }

    @Test
    void computeOnlyTargetUsesNearestStorageAndAccountsForPendingCapacity() {
        NodeManagement compute = node(4, "compute");
        List<NodeManagement> pool = new ArrayList<>(nodes.selectAllNodes()); pool.add(compute);
        when(nodes.selectAllNodes()).thenReturn(pool);
        Map<Integer, NetworkTopologyService.NetworkPath> paths = new HashMap<>();
        paths.put(2, new NetworkTopologyService.NetworkPath(Arrays.asList(4, 2), 2, 100));
        paths.put(3, new NetworkTopologyService.NetworkPath(Arrays.asList(4, 3), 10, 100));
        when(topology.pathsFrom(4)).thenReturn(paths);
        assertEquals(2, service.preview("aggregation", Collections.singletonList(10L), 4)
                .getAssignments().get(0).getTargetNodeId());
        when(datasets.countReservedStorageSlots(2)).thenReturn(10);
        assertEquals(3, service.preview("aggregation", Collections.singletonList(10L), 4)
                .getAssignments().get(0).getTargetNodeId());
        when(datasets.countStorageSlots(3)).thenReturn(10);
        assertTrue(service.preview("aggregation", Collections.singletonList(10L), 4).getAssignments().isEmpty());
    }

    @Test
    void rejectsInvalidTargetsSelectionsAndNewOccupancyAfterPreview() {
        assertThrows(RegistrationException.class, () -> service.preview("aggregation", Arrays.asList(10L, 10L), 2));
        assertThrows(RegistrationException.class, () -> service.preview("aggregation", Collections.singletonList(10L), 1));
        assertThrows(RegistrationException.class, () -> service.preview("aggregation", Collections.singletonList(999L), 2));
        DatasetStoragePlan.Submit request = new DatasetStoragePlan.Submit();
        request.setMode("aggregation"); request.setExternalPlanId("changed-occupancy");
        request.setDatasetIds(Collections.singletonList(10L)); request.setTargetNodeId(2);
        request.setAssignments(service.preview("aggregation", Collections.singletonList(10L), 2).getAssignments());
        assertFalse(request.getAssignments().isEmpty());
        when(datasets.countActiveTaskReferences(10L, "same-name")).thenReturn(1);
        assertThrows(RegistrationException.class, () -> service.submit(request));
        verifyNoInteractions(scheduling);
    }

    @Test
    void aggregationDoesNotCopyADatasetThatAlreadyHasTheMaximumReplicas() {
        when(datasets.listReplicas(10L)).thenReturn(Arrays.asList(
                availableReplica(110L, 10L, 1, "/dataset/10.npz"),
                availableReplica(310L, 10L, 3, "/dataset/10.npz")));

        DatasetStoragePlan plan = service.preview("aggregation", Collections.singletonList(10L), 2);

        assertTrue(plan.getAssignments().isEmpty());
        assertTrue(plan.getNotices().get(0).contains("上限"));
    }

    @Test
    void policyReportsTheHeatPlacementThresholds() {
        assertEquals(30.0, service.policy().get("heatThreshold"));
        assertEquals(1.0, service.policy().get("minLatencyGainMs"));
    }

    private RegisteredDataset dataset(long id, double heat) {
        return RegisteredDataset.builder().datasetId(id).name("same-name").status("ACTIVE").dataHeat(heat).build();
    }
    private DatasetReplica availableReplica(long replicaId, long datasetId, int nodeId, String path) {
        return DatasetReplica.builder().replicaId(replicaId).datasetId(datasetId).nodeId(nodeId)
                .filePath(path).sizeBytes(123L).checksumAlgorithm("SHA-256")
                .checksum("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .availability("AVAILABLE").verifiedAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }
    private NodeManagement node(int id, String type) {
        return NodeManagement.builder().nodeId(id).nodeName("node-" + id).type(type).numDataset(10)
                .enabled(true).registrationStatus("ACTIVE").observedStatus("ONLINE")
                .lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }
}
