package org.example.service;

import org.example.dto.scheduling.DatasetStoragePlan;
import org.example.dto.scheduling.SchedulingPlanRequest;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.TaskManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 热敏存储 on the ZJ topology: hz and core sites joined via master-141, sh via master-40. */
class DatasetHeatPlacementTest {
    private static final int MASTER_141 = 1, MASTER_215 = 2, MASTER_40 = 3, HZ_1 = 4, SH_3 = 5, SH_2 = 10, SH_1 = 11;

    DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
    NodeManagementMapper nodes = mock(NodeManagementMapper.class);
    NetworkTopologyService topology = mock(NetworkTopologyService.class);
    SchedulingService scheduling = mock(SchedulingService.class);
    Map<Integer, NodeManagement> pool = new LinkedHashMap<>();
    List<double[]> links = new ArrayList<>();
    List<RegisteredDataset> catalog = new ArrayList<>();

    @BeforeEach
    void setup() {
        add(node(MASTER_141, "master-141", "storage", "core"));
        add(node(MASTER_215, "master-215", "compute", "core"));
        add(node(MASTER_40, "master-40", "compute-storage", "core"));
        add(node(HZ_1, "cluster-hz-1", "storage", "hz"));
        add(node(SH_3, "cluster-sh-3", "storage", "sh"));
        add(node(SH_2, "cluster-sh-2", "storage", "sh"));
        add(node(SH_1, "cluster-sh-1", "compute", "sh"));
        link(MASTER_141, MASTER_40, 0.081, 926);
        link(MASTER_141, MASTER_215, 0.091, 938);
        link(MASTER_215, MASTER_40, 0.238, 924);
        link(MASTER_141, HZ_1, 6.355, 140);
        link(MASTER_40, SH_1, 14.245, 6);
        link(SH_3, SH_1, 0.324, 4379);
        link(SH_2, SH_1, 0.339, 4231);
        link(SH_3, SH_2, 0.472, 4432);
        when(nodes.selectAllNodes()).thenReturn(new ArrayList<>(pool.values()));
        when(nodes.getComputeCapableNodes()).thenReturn(Arrays.asList(
                pool.get(MASTER_215), pool.get(MASTER_40), pool.get(SH_1)));
        pool.values().forEach(n -> when(nodes.getNodeById(n.getNodeId())).thenReturn(n));
        when(datasets.listDatasets(null, null)).thenReturn(catalog);
        stubPaths();
    }

    @Test
    void hotHzDatasetMovesOneHopTowardsMaster215NotAcrossTheNetwork() {
        dataset(7, "tiny-imagenet", 46.85, HZ_1);

        DatasetStoragePlan plan = service(30, 1).preview("heat");

        assertEquals(1, plan.getAssignments().size());
        SchedulingPlanRequest.Assignment move = plan.getAssignments().get(0);
        assertEquals("MOVE", move.getAction());
        assertEquals(HZ_1, move.getSourceNodeId());
        assertEquals(MASTER_141, move.getTargetNodeId());
        DatasetStoragePlan.Placement row = plan.getPlacements().get(0);
        assertEquals(MASTER_215, row.getConsumerNodeId());
        assertEquals("热度 46.85 ≥ 30；就近计算节点 master-215；到计算节点时延 6.45 → 0.09 ms", row.getReason());
        verifyNoInteractions(scheduling);
    }

    @Test
    void datasetsBelowTheThresholdStayWhereTheyAre() {
        dataset(7, "tiny-imagenet", 29.99, HZ_1);
        dataset(17, "fashion", 10, MASTER_141);

        DatasetStoragePlan plan = service(30, 1).preview("heat");

        assertTrue(plan.getAssignments().isEmpty());
        assertEquals(Collections.singletonList("2 个数据集热度低于阈值 30，保持不动"), plan.getNotices());
    }

    @Test
    void neverMovesOntoAComputeStorageNode() {
        // sh data runs on cluster-sh-1; the only storage on that route is the source itself.
        dataset(5, "catdog", 46.85, SH_3);
        // Core data runs on master-215 next to it; master-40 (compute-storage) is never a target.
        dataset(17, "fashion", 46.85, MASTER_141);

        DatasetStoragePlan plan = service(30, 1).preview("heat");

        assertTrue(plan.getAssignments().isEmpty());
        assertTrue(plan.getNotices().get(0).contains("cluster-sh-3 已是通往就近计算节点 cluster-sh-1"));
        assertTrue(plan.getNotices().get(1).contains("master-141 已是通往就近计算节点 master-215"));
    }

    @Test
    void smallLatencyGainsDoNotTriggerAMove() {
        dataset(7, "tiny-imagenet", 46.85, HZ_1);

        DatasetStoragePlan plan = service(30, 10).preview("heat");

        assertTrue(plan.getAssignments().isEmpty());
        String notice = plan.getNotices().get(0);
        assertTrue(notice.contains("迁到 master-141 只缩短 6.3"), notice);
        assertTrue(notice.endsWith("ms（低于 10 ms），保持在 cluster-hz-1"), notice);
    }

    @Test
    void busyFullOrAlreadyNearDatasetsAreExplainedInsteadOfMoved() {
        dataset(7, "tiny-imagenet", 46.85, HZ_1);
        DatasetStorageService service = service(30, 1);

        when(datasets.countActiveTaskReferences(7L, "tiny-imagenet")).thenReturn(1);
        assertTrue(service.preview("heat").getNotices().get(0).contains("有进行中的任务或调度，待其结束后再迁移"));

        when(datasets.countActiveTaskReferences(7L, "tiny-imagenet")).thenReturn(0);
        when(datasets.countStorageSlots(MASTER_141)).thenReturn(100);
        DatasetStoragePlan full = service.preview("heat");
        assertTrue(full.getAssignments().isEmpty());
        assertTrue(full.getNotices().get(0).contains("存储节点 master-141 容量已满"));

        when(datasets.findReplicaByNodePath(MASTER_141, "/dataset/7.npz"))
                .thenReturn(replica(700L, 7L, MASTER_141, "AVAILABLE"));
        DatasetStoragePlan near = service.preview("heat");
        assertTrue(near.getAssignments().isEmpty());
        assertTrue(near.getNotices().get(0).contains("master-141 上已有这个数据集更靠近计算节点 master-215 的副本"));
    }

    @Test
    void submitsTheReviewedMovesAndRejectsAChangedLayout() {
        dataset(7, "tiny-imagenet", 46.85, HZ_1);
        DatasetStorageService service = service(30, 1);
        DatasetStoragePlan.Submit request = new DatasetStoragePlan.Submit();
        request.setMode("heat");
        request.setExternalPlanId("heat-1");
        request.setAssignments(service.preview("heat").getAssignments());

        service.submit(request);

        ArgumentCaptor<SchedulingPlanRequest> plan = ArgumentCaptor.forClass(SchedulingPlanRequest.class);
        verify(scheduling).submitDataPlan(plan.capture());
        assertEquals("热敏存储", plan.getValue().getAlgorithm().getName());
        catalog.get(0).setDataHeat(12.0);
        assertThrows(RegistrationException.class, () -> service.submit(request));
        verify(scheduling, times(1)).submitDataPlan(any());
    }

    private DatasetStorageService service(double threshold, double minGainMs) {
        NodeAvailabilityService availability = new NodeAvailabilityService(300);
        DatasetReplicaAvailabilityService replicas = new DatasetReplicaAvailabilityService(nodes, availability);
        InPlacePlacementService placement = new InPlacePlacementService(nodes, replicas, availability, topology,
                "master-40", "10.15.16.40");
        return new DatasetStorageService(datasets, nodes, mock(TaskManagementMapper.class), availability, replicas,
                topology, scheduling, placement, threshold, minGainMs);
    }

    private void dataset(long id, String name, double heat, int nodeId) {
        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(id).name(name).status("ACTIVE")
                .dataHeat(heat).build();
        catalog.add(dataset);
        when(datasets.findDatasetById(id)).thenReturn(dataset);
        when(datasets.listReplicas(id)).thenReturn(Collections.singletonList(replica(id * 100, id, nodeId, "AVAILABLE")));
    }

    private DatasetReplica replica(long replicaId, long datasetId, int nodeId, String availability) {
        return DatasetReplica.builder().replicaId(replicaId).datasetId(datasetId).nodeId(nodeId)
                .filePath("/dataset/" + datasetId + ".npz").sizeBytes(123L).checksumAlgorithm("SHA-256")
                .checksum("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .availability(availability).verifiedAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    private void add(NodeManagement node) {
        pool.put(node.getNodeId(), node);
    }

    private void link(int a, int b, double latencyMs, long bandwidthMbps) {
        links.add(new double[]{a, b, latencyMs, bandwidthMbps});
    }

    /** Lowest-latency paths over the links, as NetworkTopologyService computes them. */
    private void stubPaths() {
        for (Integer source : pool.keySet()) {
            Map<Integer, NetworkTopologyService.NetworkPath> best = new HashMap<>();
            best.put(source, new NetworkTopologyService.NetworkPath(Collections.singletonList(source), 0.0, Long.MAX_VALUE));
            Deque<Integer> pending = new ArrayDeque<>(Collections.singletonList(source));
            while (!pending.isEmpty()) {
                int current = pending.poll();
                NetworkTopologyService.NetworkPath path = best.get(current);
                for (double[] link : links) {
                    int a = (int) link[0], b = (int) link[1];
                    if (a != current && b != current) continue;
                    int peer = a == current ? b : a;
                    double latency = path.getLatencyMs() + link[2];
                    if (best.containsKey(peer) && best.get(peer).getLatencyMs() <= latency) continue;
                    List<Integer> route = new ArrayList<>(path.getNodeIds());
                    route.add(peer);
                    best.put(peer, new NetworkTopologyService.NetworkPath(route, latency,
                            Math.min(path.getBandwidthMbps(), (long) link[3])));
                    pending.add(peer);
                }
            }
            when(topology.pathsFrom(source)).thenReturn(best);
        }
    }

    private static NodeManagement node(int id, String name, String type, String site) {
        return NodeManagement.builder().nodeId(id).nodeName(name).type(type).siteCode(site).numDataset(100)
                .internalIp(id == MASTER_40 ? "10.15.16.40" : "10.0.0." + id)
                .enabled(true).registrationStatus("ACTIVE").observedStatus("ONLINE")
                .lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }
}
