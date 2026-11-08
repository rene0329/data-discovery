package org.example.service;

import org.example.entity.EdgeManagement;
import org.example.entity.NodeManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkTopologyServiceTest {
    private List<NodeManagement> nodes;
    private List<EdgeManagement> metrics;
    private EdgeManagementMapper edgeMapper;
    private NodeManagementMapper nodeMapper;
    private NetworkTopologyService topology;

    // center: 1 storage, 2 compute, 3 compute-storage (hub)
    // hangzhou: 4 storage; beijing: 6 storage, 12 compute (gateway)
    @BeforeEach
    void setup() {
        nodes = new ArrayList<>(Arrays.asList(
                node(1, "master-141", "storage"), node(2, "master-215", "compute"),
                node(3, "master-40", "compute-storage"), node(4, "cluster-hz-1", "storage"),
                node(6, "cluster-bj-2", "storage"), node(12, "cluster-bj-1", "compute")));
        metrics = new ArrayList<>(Arrays.asList(metric(1, 2, 5, 100), metric(1, 3, 8, 60),
                metric(2, 3, 5, 100), metric(3, 4, 20, 50), metric(3, 12, 30, 40), metric(6, 12, 2, 900)));
        edgeMapper = mock(EdgeManagementMapper.class);
        nodeMapper = mock(NodeManagementMapper.class);
        when(edgeMapper.selectAllMetrics()).thenReturn(metrics);
        when(nodeMapper.selectAllNodes()).thenReturn(nodes);
        topology = new NetworkTopologyService(edgeMapper, nodeMapper, new NodeAvailabilityService(300), 1800);
    }

    @Test
    void sitesComeFromRegionalNodeNames() {
        assertEquals("center", NetworkTopologyService.siteOf("master-40"));
        assertEquals("hangzhou", NetworkTopologyService.siteOf("cluster-hz-1"));
        assertEquals("shenzhen", NetworkTopologyService.siteOf("cluster-sz-3"));
        assertEquals("gz", NetworkTopologyService.siteOf("cluster-gz-1"));
        assertEquals("center", NetworkTopologyService.siteOf("alihz"));
        assertEquals("center", NetworkTopologyService.siteOf(null));
    }

    @Test
    void derivesSiteMeshPlusOneGatewayLinkPerSiteToHub() {
        assertEquals(Arrays.asList("1-2", "1-3", "2-3", "3-4", "3-12", "6-12"), pairs(topology.links()));
    }

    @Test
    void configuredUplinkAttachesSiteGatewayToThatCenterNodeInsteadOfHub() {
        nodes.add(node(9, "cluster-sz-1", "compute"));
        nodes.add(node(8, "cluster-sz-2", "storage"));
        NetworkTopologyService uplinked = new NetworkTopologyService(edgeMapper, nodeMapper,
                new NodeAvailabilityService(300), 1800, " shenzhen : master-141 , hangzhou:master-215,beijing:missing");
        List<String> derived = pairs(uplinked.links());
        assertTrue(derived.containsAll(Arrays.asList("1-9", "2-4", "3-12", "8-9")));
        assertFalse(derived.contains("3-9"));
        assertFalse(derived.contains("3-4"));
        metrics.add(metric(2, 4, 7, 80));
        metrics.add(metric(1, 9, 3, 70));
        metrics.add(metric(8, 9, 1, 900));
        assertEquals(Arrays.asList(4, 2, 1, 9, 8), uplinked.requirePath(4, 8).getNodeIds());
    }

    @Test
    void newNodeJoinsItsSiteMeshAndNewSiteGetsOneHubLinkWithoutConfiguration() {
        nodes.add(node(13, "cluster-bj-3", "storage"));
        nodes.add(node(20, "cluster-gz-1", "storage"));
        nodes.add(node(21, "cluster-gz-2", "compute"));
        List<String> derived = pairs(topology.links());
        assertTrue(derived.containsAll(Arrays.asList("6-13", "12-13", "20-21", "3-21")));
        assertFalse(derived.contains("3-13"));
        assertFalse(derived.contains("3-20"));
        EdgeManagement unmeasured = topology.links().stream()
                .filter(edge -> edge.getSourceId() == 3 && edge.getTargetId() == 21).findFirst().get();
        assertEquals("UNKNOWN", unmeasured.getStatus());
        assertNull(unmeasured.getLatency());
    }

    @Test
    void deletedNodesLeaveTheTopologyAndMetricsOutsideTheModelAreIgnored() {
        nodes.get(5).setDeletedAt(LocalDateTime.now());
        metrics.add(metric(4, 6, 1, 1000));
        List<String> derived = pairs(topology.links());
        assertFalse(derived.stream().anyMatch(pair -> pair.endsWith("-12")));
        assertTrue(derived.contains("3-6"));
        assertFalse(derived.contains("4-6"));
    }

    @Test
    void routesHangzhouToBeijingThroughHubAndGatewayInBothDirections() {
        NetworkTopologyService.NetworkPath path = topology.requirePath(4, 6);
        assertEquals(Arrays.asList(4, 3, 12, 6), path.getNodeIds());
        assertEquals(52.0, path.getLatencyMs());
        assertEquals(40L, path.getBandwidthMbps());
        assertEquals(Arrays.asList(6, 12, 3, 4), topology.requirePath(6, 4).getNodeIds());
    }

    @Test
    void failedHubLinkReroutesInsideCenterWithoutCreatingAnotherEdge() {
        metrics.get(0).setLatency(1.0);
        metrics.get(1).setStatus("inactive");
        assertEquals(Arrays.asList(1, 2, 3), topology.requirePath(1, 3).getNodeIds());
        assertEquals(6, topology.links().size());
    }

    @Test
    void failedGatewayLinkDisconnectsSiteButKeepsStructure() {
        metrics.get(4).setStatus("inactive");
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
        assertEquals(6, topology.links().size());
        assertEquals(Arrays.asList(4), topology.requirePath(4, 4).getNodeIds());
    }

    @Test
    void staleAndUnknownMeasurementsRemainVisibleButCannotRoute() {
        metrics.get(3).setMeasurementTime(Timestamp.from(Instant.now().minusSeconds(1801)));
        assertEquals("STALE", topology.links().get(3).getStatus());
        assertEquals("active", metrics.get(3).getStatus());
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
        metrics.get(3).setMeasurementTime(null);
        assertEquals("UNKNOWN", topology.links().get(3).getStatus());
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
        assertEquals(6, topology.links().size());
    }

    @Test
    void dailyProbeRemainsUsableUntilReportingAllowanceExpiresButFailureIsImmediate() {
        NetworkTopologyService daily = new NetworkTopologyService(
                edgeMapper, nodeMapper, new NodeAvailabilityService(300), 93600);

        metrics.get(3).setMeasurementTime(Timestamp.from(Instant.now().minusSeconds(25 * 3600)));
        assertEquals("active", daily.links().get(3).getStatus());
        assertEquals(Arrays.asList(4, 3, 12, 6), daily.requirePath(4, 6).getNodeIds());

        metrics.get(3).setStatus("inactive");
        assertThrows(RegistrationException.class, () -> daily.requirePath(4, 6));
        metrics.get(3).setStatus("active");
        metrics.get(3).setMeasurementTime(Timestamp.from(Instant.now().minusSeconds(93601)));
        assertEquals("STALE", daily.links().get(3).getStatus());
        assertThrows(RegistrationException.class, () -> daily.requirePath(4, 6));
    }

    @Test
    void unavailableTransitNodeCannotBeUsedEvenWhenItsLinksAreActive() {
        nodes.get(2).setEnabled(false);
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
        assertThrows(RegistrationException.class, () -> topology.requirePath(3, 3));
    }

    @Test
    void zeroLatencyIsValidButMissingBandwidthIsNot() {
        metrics.get(3).setLatency(0.0);
        assertEquals(32.0, topology.requirePath(4, 6).getLatencyMs());
        metrics.get(3).setBandwidth(null);
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
    }

    @Test
    void rateFallbackCanUseExpiredSuccessWithoutChangingNormalRoutingOrTimestamps() {
        Instant measured = Instant.now().minusSeconds(48 * 3600);
        metrics.forEach(edge -> edge.setMeasurementTime(Timestamp.from(measured)));
        assertTrue(topology.pathsFrom(4, 900).get(6) == null);
        NetworkTopologyService.NetworkPath fallback = topology.pathsFromLastMeasurements(4).get(6);
        assertEquals(Arrays.asList(4, 3, 12, 6), fallback.getNodeIds());
        assertEquals(measured.toEpochMilli(), fallback.getOldestMeasurement().toEpochMilli());
        assertEquals("STALE", topology.links().get(3).getStatus());
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
        assertEquals(measured.toEpochMilli(), metrics.get(3).getMeasurementTime().getTime());
    }

    @Test
    void rateFallbackNeverRevivesFailedOrUnmeasuredEdgesOrDisabledTransitNodes() {
        metrics.get(3).setStatus("inactive");
        assertNull(topology.pathsFromLastMeasurements(4).get(6));
        metrics.get(3).setStatus("UNKNOWN");
        assertNull(topology.pathsFromLastMeasurements(4).get(6));
        metrics.get(3).setStatus("active");
        metrics.get(3).setMeasurementTime(null);
        assertNull(topology.pathsFromLastMeasurements(4).get(6));
        metrics.get(3).setMeasurementTime(Timestamp.from(Instant.now().minusSeconds(7200)));
        nodes.get(2).setEnabled(false);
        assertNull(topology.pathsFromLastMeasurements(4).get(6));
    }

    @Test
    void multiSourcePathsChooseTheLowestCostReplicaForEachTarget() {
        java.util.Map<Integer, NetworkTopologyService.NetworkPath> paths = topology.pathsFromSources(Arrays.asList(4, 6));
        assertEquals(Arrays.asList(4, 3, 2), paths.get(2).getNodeIds());
        assertEquals(25.0, paths.get(2).getLatencyMs());
        assertEquals(Arrays.asList(6, 12), paths.get(12).getNodeIds());
        assertEquals(2.0, paths.get(12).getLatencyMs());
        assertEquals(Arrays.asList(4), paths.get(4).getNodeIds());
        assertEquals(Arrays.asList(6), paths.get(6).getNodeIds());
    }

    @Test
    void multiSourcePathsRelaxAnEarlierLongRouteAndKeepItsPredecessors() {
        metrics.get(1).setLatency(100.0);
        java.util.Map<Integer, NetworkTopologyService.NetworkPath> paths = topology.pathsFromSources(Arrays.asList(1, 6));
        assertEquals(Arrays.asList(1, 2, 3, 4), paths.get(4).getNodeIds());
        assertEquals(30.0, paths.get(4).getLatencyMs());
        assertEquals(50L, paths.get(4).getBandwidthMbps());
    }

    @Test
    void multiSourcePathsExcludeFailedStaleAndUnavailableTransitNodes() {
        metrics.get(4).setStatus("inactive");
        java.util.Map<Integer, NetworkTopologyService.NetworkPath> paths = topology.pathsFromSources(Arrays.asList(4, 6));
        assertEquals(Arrays.asList(6, 12), paths.get(12).getNodeIds());
        assertEquals(Arrays.asList(4, 3), paths.get(3).getNodeIds());
        metrics.get(3).setMeasurementTime(Timestamp.from(Instant.now().minusSeconds(1801)));
        assertNull(topology.pathsFromSources(Arrays.asList(4, 6)).get(3));
        nodes.get(2).setEnabled(false);
        assertFalse(topology.pathsFromSources(Arrays.asList(3, 6)).containsKey(3));
        assertTrue(topology.pathsFromSources(Arrays.asList(3, 999)).isEmpty());
        assertTrue(topology.pathsFromSources(java.util.Collections.emptyList()).isEmpty());
    }

    @Test
    void multiSourceZeroCostCyclesTerminateAndEqualCostsAreReproducible() {
        metrics.get(0).setLatency(0.0);
        metrics.get(1).setLatency(0.0);
        metrics.get(2).setLatency(0.0);
        java.util.Map<Integer, NetworkTopologyService.NetworkPath> forward = topology.pathsFromSources(Arrays.asList(1, 2));
        java.util.Map<Integer, NetworkTopologyService.NetworkPath> reversed = topology.pathsFromSources(Arrays.asList(2, 1, 2));
        assertEquals(Arrays.asList(1), forward.get(1).getNodeIds());
        assertEquals(Arrays.asList(2), forward.get(2).getNodeIds());
        assertEquals(Arrays.asList(1, 3), forward.get(3).getNodeIds());
        assertEquals(forward.get(4).getNodeIds(), reversed.get(4).getNodeIds());
        assertEquals(20.0, forward.get(4).getLatencyMs());
    }

    @Test
    void placementAndResourceReservationsUseTheRealMultiSourceRoutes() {
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Arrays.asList(nodes.get(1), nodes.get(2), nodes.get(5)));
        for (NodeManagement node : nodes) when(nodeMapper.getNodeById(node.getNodeId())).thenReturn(node);
        DatasetReplicaAvailabilityService replicas = mock(DatasetReplicaAvailabilityService.class);
        when(replicas.evaluate(any())).thenReturn(new ReplicaAvailability("AVAILABLE", true, null));
        InPlacePlacementService placement = new InPlacePlacementService(nodeMapper, replicas,
                new NodeAvailabilityService(300), topology, "master-40", "");
        List<org.example.entity.DatasetReplica> inputs = Arrays.asList(
                org.example.entity.DatasetReplica.builder().replicaId(1L).nodeId(4).build(),
                org.example.entity.DatasetReplica.builder().replicaId(2L).nodeId(6).build());
        NodeResourceLedger ledger = new NodeResourceLedger();
        for (NodeManagement node : nodeMapper.getComputeCapableNodes()) ledger.put(node.getNodeName(), 3, 8);
        JobResourceDemand demand = JobResourceDemand.of(3.0, 1.0);
        InPlacePlacementService.Placement first = placement.place(inputs, nodeMapper.getComputeCapableNodes(), demand, ledger);
        assertEquals("cluster-bj-1", first.getComputeNode().getNodeName());
        assertEquals(2L, first.getReplica().getReplicaId());
        InPlacePlacementService.Placement second = placement.place(inputs, nodeMapper.getComputeCapableNodes(), demand, ledger);
        assertEquals("master-40", second.getComputeNode().getNodeName());
        assertEquals(1L, second.getReplica().getReplicaId());
        assertEquals(Arrays.asList(4, 3), second.getPath().getNodeIds());
        assertEquals("master-215", placement.place(inputs, nodeMapper.getComputeCapableNodes(), demand, ledger)
                .getComputeNode().getNodeName());
        assertFalse(placement.place(inputs, nodeMapper.getComputeCapableNodes(), demand, ledger).isFound());
    }

    private List<String> pairs(List<EdgeManagement> links) {
        return links.stream().map(edge -> edge.getSourceId() + "-" + edge.getTargetId()).collect(Collectors.toList());
    }

    private NodeManagement node(int id, String name, String type) {
        return NodeManagement.builder().nodeId(id).nodeName(name).type(type).enabled(true)
                .registrationStatus("ACTIVE").observedStatus("ONLINE")
                .lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    private EdgeManagement metric(int source, int target, double latency, long bandwidth) {
        return EdgeManagement.builder().sourceId(source).targetId(target)
                .latency(latency).bandwidth(bandwidth).status("active")
                .measurementTime(Timestamp.from(Instant.now())).build();
    }
}
