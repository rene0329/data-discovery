package org.example.service;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.mapper.NodeManagementMapper;
import org.example.service.NetworkTopologyService.NetworkPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InPlacePlacementServiceTest {
    private final NodeManagement storage = node(5, "cluster-hz-1", "storage", "hz");
    private final NodeManagement central = node(1, "master-40", "compute-storage", "center");
    private final NodeManagement compute = node(3, "master-215", "compute", "center");
    private final NodeManagement regional = node(7, "cluster-hz-2", "compute", "hz");
    private NodeManagementMapper nodes;
    private DatasetReplicaAvailabilityService replicas;
    private NodeAvailabilityService availability;
    private NetworkTopologyService topology;
    private InPlacePlacementService placement;
    // Explicit expected routes supplied by the topology fixture, not computed by placement tests.
    private final Map<Integer, NetworkPath> nearest = new HashMap<>();

    @BeforeEach void setup() {
        nodes = mock(NodeManagementMapper.class);
        replicas = mock(DatasetReplicaAvailabilityService.class);
        availability = mock(NodeAvailabilityService.class);
        topology = mock(NetworkTopologyService.class);
        when(replicas.evaluate(any())).thenReturn(new ReplicaAvailability("AVAILABLE", true, null));
        when(availability.isSchedulable(any())).thenReturn(true);
        for (NodeManagement node : Arrays.asList(storage, central, compute, regional))
            when(nodes.getNodeById(node.getNodeId())).thenReturn(node);
        when(nodes.getComputeCapableNodes()).thenReturn(Arrays.asList(central, compute, regional));
        when(topology.pathsFromSources(anyCollection())).thenReturn(nearest);
        placement = new InPlacePlacementService(nodes, replicas, availability, topology, "master-40", "10.15.16.40");
    }

    @Test void crossSiteLowerCostWinsOverSameSiteAndCenterIsNotDeferred() {
        routes(5, path(5, 1, 1, 100), path(5, 7, 30, 1000));
        InPlacePlacementService.Placement result = placement.place(Collections.singletonList(replica(1, 5)));
        assertEquals("master-40", result.getComputeNode().getNodeName());
        assertEquals(InPlacePlacementService.Tier.NEAREST, result.getTier());
        assertEquals(1.0, result.getPath().getLatencyMs());
    }

    @Test void sameSiteTargetsAreAlsoComparedByMeasuredCost() {
        regional.setSiteCode("center");
        storage.setSiteCode("center");
        routes(5, path(5, 1, 20, 100), path(5, 3, 5, 100), path(5, 7, 10, 100));
        InPlacePlacementService.Placement result = placement.place(Collections.singletonList(replica(1, 5)));
        assertEquals("master-215", result.getComputeNode().getNodeName());
        assertEquals(InPlacePlacementService.Tier.SAME_SITE, result.getTier());
    }

    @Test void sameSiteWithoutMeasuredPathIsNotAdmitted() {
        InPlacePlacementService.Placement result = placement.place(Collections.singletonList(replica(1, 5)));
        assertFalse(result.isFound());
        assertTrue(result.getDecisionEvents().stream().anyMatch(event ->
                "PLACEMENT_PATH".equals(event.get("eventType")) && "UNAVAILABLE".equals(event.get("outcome"))));
        assertTrue(placement.candidates(Collections.singletonList(replica(1, 5)), Arrays.asList(regional)).isEmpty());
    }

    @Test void localReplicaNeedsNoMeasuredLinkAndRecordsAllSourceTargetPaths() {
        InPlacePlacementService.Placement result = placement.place(Arrays.asList(replica(1, 5), replica(2, 7)));
        assertEquals("cluster-hz-2", result.getComputeNode().getNodeName());
        assertEquals(InPlacePlacementService.Tier.LOCAL, result.getTier());
        assertEquals(Collections.singletonList(7), result.getPath().getNodeIds());
        List<Map<String, Object>> paths = result.getDecisionEvents().stream()
                .filter(event -> "PLACEMENT_PATH".equals(event.get("eventType"))).collect(Collectors.toList());
        assertEquals(6, paths.size());
        assertEquals(5, paths.stream().filter(event -> "UNAVAILABLE".equals(event.get("outcome"))).count());
        assertEquals(0.0, paths.stream().filter(event -> "RECORDED".equals(event.get("outcome")))
                .findFirst().get().get("latencyMs"));
    }

    @Test void equalCostsUseNodeIdWithoutBandwidthOrCenterPriority() {
        routes(5, path(5, 1, 10, 10), path(5, 3, 10, 1000), path(5, 7, 10, 500));
        assertEquals("master-40", placement.place(Collections.singletonList(replica(1, 5)))
                .getComputeNode().getNodeName());
    }

    @Test void eachTargetUsesItsNearestReplicaAndAppearsOnlyOnce() {
        routes(5, path(5, 1, 20, 100));
        routes(7, path(7, 1, 2, 100), path(7, 3, 5, 100));
        List<InPlacePlacementService.Placement> choices = placement.candidates(
                Arrays.asList(replica(1, 5), replica(2, 7)), Arrays.asList(central, compute, regional));
        assertEquals(Arrays.asList("cluster-hz-2", "master-40", "master-215"), choices.stream()
                .map(item -> item.getComputeNode().getNodeName()).collect(Collectors.toList()));
        assertEquals(2L, choices.get(1).getReplica().getReplicaId());
        assertEquals(Arrays.asList(7, 1), choices.get(1).getPath().getNodeIds());
        verify(topology).pathsFromSources(eq(new LinkedHashSet<>(Arrays.asList(5, 7))));
    }

    @Test void inputReplicaOrderDoesNotChangeLocalTieResolution() {
        List<InPlacePlacementService.Placement> choices = placement.candidates(
                Arrays.asList(replica(2, 7), replica(1, 1)), Arrays.asList(regional, central));
        assertEquals("master-40", choices.get(0).getComputeNode().getNodeName());
    }

    @Test void unusableReplicaIsNotASourceAndUnschedulableComputeIsNotATarget() {
        DatasetReplica unusable = replica(2, 7);
        when(replicas.evaluate(unusable)).thenReturn(new ReplicaAvailability("MISSING", false, "missing"));
        when(availability.isSchedulable(central)).thenReturn(false);
        routes(5, path(5, 1, 1, 100), path(5, 3, 5, 100));
        List<InPlacePlacementService.Placement> choices = placement.candidates(
                Arrays.asList(replica(1, 5), unusable), Arrays.asList(central, compute));
        assertEquals(1, choices.size());
        assertEquals("master-215", choices.get(0).getComputeNode().getNodeName());
        verify(topology).pathsFromSources(eq(Collections.singleton(5)));
    }

    @Test void reservationMakesFollowingJobChooseNextMinimumCostTarget() {
        routes(5, path(5, 1, 1, 100), path(5, 3, 5, 100));
        NodeResourceLedger ledger = ledger(4, 4);
        JobResourceDemand demand = JobResourceDemand.of(3.0, 1.0);
        InPlacePlacementService.Placement first = place(demand, ledger);
        InPlacePlacementService.Placement second = place(demand, ledger);
        assertEquals("master-40", first.getComputeNode().getNodeName());
        assertEquals("master-215", second.getComputeNode().getNodeName());
        assertEquals(1.0, ledger.availableCpu("master-40"));
        assertEquals(1.0, ledger.availableCpu("master-215"));
        assertTrue(second.getResourceNote().contains("master-40 资源不足"));
        Map<String, Object> rejected = second.getDecisionEvents().stream()
                .filter(event -> "PLACEMENT_CANDIDATE".equals(event.get("eventType")))
                .findFirst().get();
        assertEquals("INSUFFICIENT", rejected.get("resourceCheck"));
    }

    @Test void allTargetsFullReturnsNoPlacementWithoutFurtherReservation() {
        routes(5, path(5, 1, 1, 100), path(5, 3, 5, 100));
        NodeResourceLedger ledger = ledger(1, 1);
        assertFalse(place(JobResourceDemand.of(3.0, 1.0), ledger).isFound());
        assertEquals(1.0, ledger.availableCpu("master-40"));
        assertEquals(1.0, ledger.availableCpu("master-215"));
    }

    @Test void unknownResourcesAreSkippedAndResourceChecksArePerJob() {
        routes(5, path(5, 1, 1, 100), path(5, 3, 5, 100));
        NodeResourceLedger small = ledger(1, 4);
        assertEquals("master-40", place(JobResourceDemand.of(.5, 1.0), small).getComputeNode().getNodeName());
        NodeResourceLedger large = ledger(1, 4);
        assertEquals("master-215", place(JobResourceDemand.of(3.0, 1.0), large).getComputeNode().getNodeName());
        NodeResourceLedger unknown = new NodeResourceLedger();
        unknown.put("master-215", 4, 8);
        assertEquals("master-215", place(JobResourceDemand.of(3.0, 1.0), unknown).getComputeNode().getNodeName());
    }

    @Test void fullLocalNodeCanFallBackToTheCentralNode() {
        routes(7, path(7, 1, 1, 100), path(7, 3, 5, 100));
        NodeResourceLedger ledger = ledger(4, 4);
        ledger.put("cluster-hz-2", .5, 8);
        InPlacePlacementService.Placement result = placement.place(Collections.singletonList(replica(1, 7)),
                Arrays.asList(central, compute, regional), JobResourceDemand.of(3.0, 1.0), ledger);
        assertEquals("master-40", result.getComputeNode().getNodeName());
        assertTrue(result.getResourceNote().contains("cluster-hz-2 资源不足"));
    }

    private InPlacePlacementService.Placement place(JobResourceDemand demand, NodeResourceLedger ledger) {
        return placement.place(Collections.singletonList(replica(1, 5)), Arrays.asList(central, compute), demand, ledger);
    }
    private NodeResourceLedger ledger(double first, double second) {
        NodeResourceLedger ledger = new NodeResourceLedger();
        ledger.put("master-40", first, 8); ledger.put("master-215", second, 8);
        return ledger;
    }
    private void routes(int source, NetworkPath... targets) {
        Map<Integer, NetworkPath> paths = new HashMap<>();
        paths.put(source, new NetworkPath(Collections.singletonList(source), 0, Long.MAX_VALUE));
        for (NetworkPath path : targets) paths.put(path.getNodeIds().get(path.getNodeIds().size() - 1), path);
        when(topology.pathsFrom(source)).thenReturn(paths);
        nearest.putAll(paths);
    }
    private NetworkPath path(int source, int target, double latency, long bandwidth) {
        return new NetworkPath(Arrays.asList(source, target), latency, bandwidth);
    }
    private DatasetReplica replica(long id, int node) {
        return DatasetReplica.builder().replicaId(id).datasetId(10L).nodeId(node).build();
    }
    private NodeManagement node(int id, String name, String type, String site) {
        return NodeManagement.builder().nodeId(id).nodeName(name).type(type).siteCode(site).build();
    }
}
