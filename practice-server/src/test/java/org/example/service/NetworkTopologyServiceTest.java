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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkTopologyServiceTest {
    private List<NodeManagement> nodes;
    private List<EdgeManagement> links;
    private NetworkTopologyService topology;

    @BeforeEach
    void setup() {
        nodes = Arrays.asList(node(1, "master-88"), node(2, "master-89"), node(3, "master-90"),
                node(4, "alihz"), node(5, "alish"), node(6, "alibj"));
        links = new ArrayList<>(Arrays.asList(edge(1, 2, 5, 100), edge(1, 3, 8, 60),
                edge(2, 3, 5, 100), edge(1, 4, 20, 50), edge(1, 5, 15, 70), edge(3, 6, 30, 40)));
        EdgeManagementMapper edgeMapper = mock(EdgeManagementMapper.class);
        NodeManagementMapper nodeMapper = mock(NodeManagementMapper.class);
        when(edgeMapper.links()).thenReturn(links);
        when(nodeMapper.selectAllNodes()).thenReturn(nodes);
        topology = new NetworkTopologyService(edgeMapper, nodeMapper, new NodeAvailabilityService(300), 1800);
    }

    @Test
    void routesHangzhouToBeijingThrough88And90InBothDirections() {
        NetworkTopologyService.NetworkPath path = topology.requirePath(4, 6);
        assertEquals(Arrays.asList(4, 1, 3, 6), path.getNodeIds());
        assertEquals(58.0, path.getLatencyMs());
        assertEquals(40L, path.getBandwidthMbps());
        assertEquals(Arrays.asList(6, 3, 1, 4), topology.requirePath(6, 4).getNodeIds());
        assertEquals(Arrays.asList(4, 1, 5), topology.requirePath(4, 5).getNodeIds());
    }

    @Test
    void failedCenterLinkReroutesVia89WithoutCreatingAnotherEdge() {
        links.get(1).setStatus("inactive");
        NetworkTopologyService.NetworkPath path = topology.requirePath(4, 6);
        assertEquals(Arrays.asList(4, 1, 2, 3, 6), path.getNodeIds());
        assertEquals(60.0, path.getLatencyMs());
        assertEquals(6, topology.links().size());
    }

    @Test
    void failedLeafLinkDisconnectsLeafButKeepsStructure() {
        links.get(3).setStatus("inactive");
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
        assertEquals(6, topology.links().size());
        assertEquals(Arrays.asList(4), topology.requirePath(4, 4).getNodeIds());
    }

    @Test
    void staleAndUnknownMeasurementsRemainVisibleButCannotRoute() {
        links.get(3).setMeasurementTime(Timestamp.from(Instant.now().minusSeconds(1801)));
        assertEquals("STALE", topology.links().get(3).getStatus());
        assertEquals("active", links.get(3).getStatus());
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
        links.get(3).setMeasurementTime(null);
        assertEquals("UNKNOWN", topology.links().get(3).getStatus());
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
        assertEquals(6, topology.links().size());
    }

    @Test
    void unavailableTransitNodeCannotBeUsedEvenWhenItsLinksAreActive() {
        nodes.get(0).setEnabled(false);
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
        assertThrows(RegistrationException.class, () -> topology.requirePath(1, 1));
    }

    @Test
    void zeroLatencyIsValidButMissingBandwidthIsNot() {
        links.get(3).setLatency(0.0);
        assertEquals(38.0, topology.requirePath(4, 6).getLatencyMs());
        links.get(3).setBandwidth(null);
        assertThrows(RegistrationException.class, () -> topology.requirePath(4, 6));
    }

    private NodeManagement node(int id, String name) {
        return NodeManagement.builder().nodeId(id).nodeName(name).enabled(true).registrationStatus("ACTIVE")
                .observedStatus("ONLINE").lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    private EdgeManagement edge(int source, int target, double latency, long bandwidth) {
        return EdgeManagement.builder().edgeId(source * 10 + target).sourceId(source).targetId(target)
                .latency(latency).bandwidth(bandwidth).status("active")
                .measurementTime(Timestamp.from(Instant.now())).build();
    }
}
