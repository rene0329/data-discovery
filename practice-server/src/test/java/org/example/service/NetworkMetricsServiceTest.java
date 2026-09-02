package org.example.service;

import org.example.dto.NetworkMetricDto;
import org.example.entity.EdgeManagement;
import org.example.entity.NodeManagement;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NetworkMetricsServiceTest {
    private EdgeManagementMapper edges;
    private NodeManagementMapper nodes;
    private NetworkMetricsService service;

    @BeforeEach
    void setUp() {
        edges = mock(EdgeManagementMapper.class);
        nodes = mock(NodeManagementMapper.class);
        service = new NetworkMetricsService(edges, nodes, mock(NetworkTopologyService.class));
        when(nodes.getNodeByName("alihz")).thenReturn(NodeManagement.builder().nodeId(14).nodeName("alihz").build());
        when(nodes.getNodeByName("master-88")).thenReturn(NodeManagement.builder().nodeId(7).nodeName("master-88").build());
        when(nodes.getNodeByName("master-90")).thenReturn(NodeManagement.builder().nodeId(9).nodeName("master-90").build());
    }

    @Test
    void ignoresSuccessfulProbeOutsideApprovedTopology() {
        service.saveMetrics(report("alihz", "master-90", 90_000_000L, 2.0));
        verify(edges).findBySourceAndTargetNode(9, 14);
        verify(edges, never()).updateEdge(any());
    }

    @Test
    void updatesApprovedUndirectedLinkInEitherDirectionWithoutChangingEndpoints() {
        EdgeManagement edge = EdgeManagement.builder().edgeId(4).sourceId(7).targetId(14).status("UNKNOWN").build();
        when(edges.findBySourceAndTargetNode(7, 14)).thenReturn(edge);
        service.saveMetrics(report("alihz", "master-88", 93_666_000L, 33.7));
        service.saveMetrics(report("master-88", "alihz", 95_000_000L, 34.0));
        verify(edges, times(2)).updateEdge(edge);
        assertEquals(4, edge.getEdgeId());
        assertEquals(7, edge.getSourceId());
        assertEquals(14, edge.getTargetId());
        assertEquals(95L, edge.getBandwidth());
        assertEquals(34.0, edge.getLatency());
        assertEquals("active", edge.getStatus());
    }

    @Test
    void failureKeepsLastMetricsAndRecoveryReusesSameLink() {
        EdgeManagement edge = EdgeManagement.builder().edgeId(4).sourceId(7).targetId(14)
                .bandwidth(80L).latency(20.0).status("active").build();
        when(edges.findBySourceAndTargetNode(7, 14)).thenReturn(edge);
        service.saveMetrics(report("alihz", "master-88", null, null));
        assertEquals("inactive", edge.getStatus());
        assertEquals(80L, edge.getBandwidth());
        assertEquals(20.0, edge.getLatency());
        service.saveMetrics(report("alihz", "master-88", 70_000_000L, 25.0));
        assertEquals("active", edge.getStatus());
        assertEquals(4, edge.getEdgeId());
        assertEquals(70L, edge.getBandwidth());
    }

    @Test
    void incompleteOrNonFiniteMeasurementsCannotActivateLink() {
        EdgeManagement edge = EdgeManagement.builder().edgeId(4).build();
        when(edges.findBySourceAndTargetNode(7, 14)).thenReturn(edge);
        service.saveMetrics(report("alihz", "master-88", null, 33.7));
        assertEquals("inactive", edge.getStatus());
        service.saveMetrics(report("alihz", "master-88", 100L, Double.NaN));
        assertEquals("inactive", edge.getStatus());
        assertNull(edge.getBandwidth());
    }

    private NetworkMetricDto report(String source, String target, Long bps, Double latency) {
        return NetworkMetricDto.builder().sourceNode(source).targetNode(target)
                .bandwidthBps(bps).latencyMs(latency).build();
    }
}
