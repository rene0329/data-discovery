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
