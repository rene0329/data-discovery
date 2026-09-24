package org.example.service;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.mapper.NodeManagementMapper;
import org.example.service.NetworkTopologyService.NetworkPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InPlacePlacementServiceTest {
    private NodeManagementMapper nodes;
    private DatasetReplicaAvailabilityService replicas;
    private NodeAvailabilityService nodeAvailability;
    private NetworkTopologyService topology;
    private InPlacePlacementService placement;

    // hz has only a storage node; the other nodes are compute nodes in other sites.
    private final NodeManagement hzStorage = node(5, "cluster-hz-1", "storage", "hz");
    private final NodeManagement master40 = node(1, "master-40", "compute-storage", "center");
    private final NodeManagement master215 = node(3, "master-215", "compute", "center");
    private final NodeManagement sh1 = node(7, "cluster-sh-1", "compute", "sh");

    @BeforeEach
    void setUp() {
        nodes = mock(NodeManagementMapper.class);
        replicas = mock(DatasetReplicaAvailabilityService.class);
        nodeAvailability = mock(NodeAvailabilityService.class);
        topology = mock(NetworkTopologyService.class);
        when(replicas.evaluate(any())).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodeAvailability.isSchedulable(any())).thenReturn(true);
        for (NodeManagement node : Arrays.asList(hzStorage, master40, master215, sh1)) {
            when(nodes.getNodeById(node.getNodeId())).thenReturn(node);
        }
        when(nodes.getComputeCapableNodes()).thenReturn(Arrays.asList(master40, master215, sh1));
        placement = new InPlacePlacementService(nodes, replicas, nodeAvailability, topology);
    }

    @Test
    void replicaOnAComputeNodeRunsInPlaceWithoutConsultingTopology() {
        InPlacePlacementService.Placement result = placement.place(Arrays.asList(replica(1, 5), replica(2, 7)));

        assertEquals(InPlacePlacementService.Tier.LOCAL, result.getTier());
        assertEquals("cluster-sh-1", result.getComputeNode().getNodeName());
        verify(topology, never()).pathsFrom(any());
    }

    @Test
    void sameSiteComputeNodeWinsOverACloserCrossSiteNode() {
        NodeManagement shStorage = node(8, "cluster-sh-2", "storage", "sh");
        when(nodes.getNodeById(8)).thenReturn(shStorage);
        paths(8, path(1, 1.0, 1000L), path(7, 30.0, 10L));

        InPlacePlacementService.Placement result = placement.place(Collections.singletonList(replica(1, 8)));

        assertEquals(InPlacePlacementService.Tier.SAME_SITE, result.getTier());
        assertEquals("cluster-sh-1", result.getComputeNode().getNodeName());
        assertNull(result.getPath());
        verify(topology, never()).pathsFrom(any());
    }

    @Test
    void fallbackPicksTheLowestLatencyComputeNode() {
        paths(5, path(1, 12.0, 1000L), path(3, 8.0, 50L), path(7, 40.0, 1000L));

        InPlacePlacementService.Placement result = placement.place(Collections.singletonList(replica(1, 5)));

        assertTrue(result.isFound());
        assertEquals(InPlacePlacementService.Tier.NEAREST, result.getTier());
        assertEquals("cluster-hz-1", result.getReplicaNode().getNodeName());
        assertEquals("master-215", result.getComputeNode().getNodeName());
        assertEquals(8.0, result.getPath().getLatencyMs());
    }

    @Test
    void fallbackBreaksLatencyTiesByBottleneckBandwidthThenNodeId() {
        paths(5, path(1, 10.0, 100L), path(3, 10.0, 500L), path(7, 10.0, 500L));

        assertEquals("master-215", placement.place(Collections.singletonList(replica(1, 5)))
                .getComputeNode().getNodeName());
    }

    @Test
    void fallbackConsidersEveryUsableReplicaAndReplicasWithoutSiteCode() {
        NodeManagement unsited = node(9, "storage-x", "storage", null);
        when(nodes.getNodeById(9)).thenReturn(unsited);
        paths(5, path(1, 20.0, 100L));
        paths(9, path(3, 5.0, 100L));

        InPlacePlacementService.Placement result = placement.place(Arrays.asList(replica(1, 5), replica(2, 9)));

        assertEquals(2L, result.getReplica().getReplicaId());
        assertEquals("master-215", result.getComputeNode().getNodeName());
    }

    @Test
    void fallbackIgnoresUnschedulableComputeNodes() {
        when(nodeAvailability.isSchedulable(master215)).thenReturn(false);
        paths(5, path(1, 12.0, 100L), path(3, 8.0, 100L));

        assertEquals("master-40", placement.place(Collections.singletonList(replica(1, 5)))
                .getComputeNode().getNodeName());
    }

    @Test
    void noReachableComputeNodeIsNotAPlacement() {
        paths(5);

        InPlacePlacementService.Placement result = placement.place(Collections.singletonList(replica(1, 5)));

        assertFalse(result.isFound());
        assertEquals(Collections.singletonList("cluster-hz-1: no available compute node in site 'hz', "
                + "and no reachable compute node from cluster-hz-1"), result.getRejectedReasons());
    }

    @Test
    void distributedRunsSkipTheCentralNodeEvenWhenItIsMarginallyCloser() {
        // ZJ: cluster-hz-1 reaches master-40 in 6.436 ms and master-215 in 6.446 ms, both via master-141.
        paths(5, path(1, 6.436, 140L), path(3, 6.446, 140L), path(7, 20.0, 100L));

        InPlacePlacementService.Placement result = centralAware("master-40", "")
                .place(Collections.singletonList(replica(1, 5)));

        assertEquals(InPlacePlacementService.Tier.NEAREST, result.getTier());
        assertEquals("master-215", result.getComputeNode().getNodeName());
    }

    @Test
    void sameSitePlacementSkipsTheCentralNode() {
        NodeManagement master141 = node(2, "master-141", "storage", "center");
        when(nodes.getNodeById(2)).thenReturn(master141);

        InPlacePlacementService.Placement result = centralAware("master-40", "")
                .place(Collections.singletonList(replica(1, 2)));

        assertEquals(InPlacePlacementService.Tier.SAME_SITE, result.getTier());
        assertEquals("master-215", result.getComputeNode().getNodeName());
    }

    @Test
    void aReplicaOnTheCentralNodeStillRunsThere() {
        InPlacePlacementService.Placement result = centralAware("master-40", "")
                .place(Collections.singletonList(replica(1, 1)));

        assertEquals(InPlacePlacementService.Tier.LOCAL, result.getTier());
        assertEquals("master-40", result.getComputeNode().getNodeName());
    }

    @Test
    void theCentralNodeIsUsedWhenNoOtherComputeNodeIsReachable() {
        paths(5, path(1, 12.0, 100L));

        InPlacePlacementService.Placement result = centralAware("master-40", "")
                .place(Collections.singletonList(replica(1, 5)));

        assertEquals("master-40", result.getComputeNode().getNodeName());
        assertTrue(result.getRejectedReasons().isEmpty());
    }

    @Test
    void theCentralNodeIsRecognisedByItsInternalIp() {
        master40.setInternalIp("10.15.16.40");
        paths(5, path(1, 6.436, 140L), path(3, 6.446, 140L));

        assertEquals("master-215", centralAware("", "10.15.16.40")
                .place(Collections.singletonList(replica(1, 5))).getComputeNode().getNodeName());
    }

    @Test
    void aFullSameSiteComputeNodeFallsBackToTheSecondBestNode() {
        // The outline's case: yelp (sz-2) and epinions (sz-3) both prefer cluster-sz-1, which has room
        // for one 2-CPU Job; epinions then runs on master-215, and master-40 stays excluded.
        NodeManagement sz1 = node(13, "cluster-sz-1", "compute", "sz");
        NodeManagement sz2 = node(12, "cluster-sz-2", "storage", "sz");
        NodeManagement sz3 = node(11, "cluster-sz-3", "storage", "sz");
        for (NodeManagement node : Arrays.asList(sz1, sz2, sz3)) when(nodes.getNodeById(node.getNodeId())).thenReturn(node);
        List<NodeManagement> compute = Arrays.asList(master40, master215, sh1, sz1);
        paths(11, path(13, 0.523, 4277L), path(3, 31.192, 114L), path(1, 31.430, 114L), path(7, 45.675, 6L));
        NodeResourceLedger ledger = new NodeResourceLedger();
        ledger.put("cluster-sz-1", 3.875, 7.18);
        ledger.put("master-215", 102.5, 228.0);
        JobResourceDemand demand = JobResourceDemand.of(2.0, 1.0);
        InPlacePlacementService placement = centralAware("master-40", "");

        InPlacePlacementService.Placement yelp = placement.place(
                Collections.singletonList(replica(1, 12)), compute, demand, ledger);
        InPlacePlacementService.Placement epinions = placement.place(
                Collections.singletonList(replica(2, 11)), compute, demand, ledger);

        assertEquals("cluster-sz-1", yelp.getComputeNode().getNodeName());
        assertNull(yelp.getResourceNote());
        assertEquals(InPlacePlacementService.Tier.NEAREST, epinions.getTier());
        assertEquals("master-215", epinions.getComputeNode().getNodeName());
        assertEquals("cluster-sz-1 资源不足（需要 2 CPU / 1 GiB，空闲 1.88 CPU / 6.18 GiB）", epinions.getResourceNote());
    }

    @Test
    void aFullLocalComputeNodeIsSkippedToo() {
        paths(7, path(3, 14.483, 6L), path(1, 14.245, 6L));
        NodeResourceLedger ledger = new NodeResourceLedger();
        ledger.put("cluster-sh-1", 0.5, 7.0);

        InPlacePlacementService.Placement result = centralAware("master-40", "").place(
                Collections.singletonList(replica(1, 7)), Arrays.asList(master40, master215, sh1),
                JobResourceDemand.of(2.0, 1.0), ledger);

        assertEquals("master-215", result.getComputeNode().getNodeName());
        assertTrue(result.getResourceNote().startsWith("cluster-sh-1 资源不足"));
    }

    @Test
    void noPlacementWhenNoReachableComputeNodeHasRoom() {
        paths(5, path(1, 6.436, 140L), path(3, 6.446, 140L));
        NodeResourceLedger ledger = new NodeResourceLedger();
        ledger.put("master-40", 1.0, 1.0);
        ledger.put("master-215", 1.0, 1.0);

        InPlacePlacementService.Placement result = centralAware("master-40", "").place(
                Collections.singletonList(replica(1, 5)), Arrays.asList(master40, master215),
                JobResourceDemand.of(2.0, 1.0), ledger);

        assertFalse(result.isFound());
        assertTrue(result.getRejectedReasons().get(0).startsWith("no compute node has enough free resources: "));
        assertTrue(result.getRejectedReasons().get(0).contains("master-215 资源不足"));
        assertTrue(result.getRejectedReasons().get(0).contains("master-40 资源不足"));
    }

    private InPlacePlacementService centralAware(String name, String ip) {
        return new InPlacePlacementService(nodes, replicas, nodeAvailability, topology, name, ip);
    }

    private void paths(int source, NetworkPath... targets) {
        Map<Integer, NetworkPath> map = new HashMap<>();
        map.put(source, new NetworkPath(Collections.singletonList(source), 0.0, Long.MAX_VALUE));
        for (NetworkPath target : targets) {
            List<Integer> ids = target.getNodeIds();
            map.put(ids.get(ids.size() - 1), target);
        }
        when(topology.pathsFrom(source)).thenReturn(map);
    }

    private static NetworkPath path(int target, double latencyMs, long bandwidthMbps) {
        return new NetworkPath(Arrays.asList(0, target), latencyMs, bandwidthMbps);
    }

    private static DatasetReplica replica(long id, int nodeId) {
        return DatasetReplica.builder().replicaId(id).datasetId(10L).nodeId(nodeId).build();
    }

    private static NodeManagement node(int id, String name, String type, String siteCode) {
        return NodeManagement.builder().nodeId(id).nodeName(name).type(type).siteCode(siteCode).build();
    }
}
