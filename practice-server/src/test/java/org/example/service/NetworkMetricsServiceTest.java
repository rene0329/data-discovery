package org.example.service;

import org.example.dto.NetworkMetricDto;
import org.example.entity.EdgeManagement;
import org.example.entity.NodeManagement;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
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
        when(nodes.getNodeByName("cluster-hz-1")).thenReturn(NodeManagement.builder().nodeId(14).nodeName("cluster-hz-1").build());
        when(nodes.getNodeByName("master-40")).thenReturn(NodeManagement.builder().nodeId(7).nodeName("master-40").build());
    }

    @Test
    void upsertsUndirectedPairInEitherDirection() {
        service.saveMetrics(report("cluster-hz-1", "master-40", 93_666_000L, 33.7));
        service.saveMetrics(report("master-40", "cluster-hz-1", 95_000_000L, 34.0));
        List<EdgeManagement> saved = captured(2);
        for (EdgeManagement edge : saved) {
            assertEquals(7, edge.getSourceId());
            assertEquals(14, edge.getTargetId());
            assertEquals("active", edge.getStatus());
        }
        assertEquals(94L, saved.get(0).getBandwidth());
        assertEquals(95L, saved.get(1).getBandwidth());
        assertEquals(34.0, saved.get(1).getLatency());
    }

    @Test
    void failureSendsNullMetricsSoStoredValuesAreKept() {
        service.saveMetrics(report("cluster-hz-1", "master-40", null, null));
        EdgeManagement edge = captured(1).get(0);
        assertEquals("inactive", edge.getStatus());
        assertNull(edge.getBandwidth());
        assertNull(edge.getLatency());
    }

    @Test
    void incompleteOrNonFiniteMeasurementsCannotActivateLink() {
        service.saveMetrics(report("cluster-hz-1", "master-40", null, 33.7));
        service.saveMetrics(report("cluster-hz-1", "master-40", 100L, Double.NaN));
        for (EdgeManagement edge : captured(2)) {
            assertEquals("inactive", edge.getStatus());
            assertNull(edge.getBandwidth());
        }
    }

    @Test
    void unknownOrSelfPairsAreIgnored() {
        service.saveMetrics(report("cluster-hz-1", "missing", 90_000_000L, 2.0));
        service.saveMetrics(report("master-40", "master-40", 90_000_000L, 2.0));
        verify(edges, never()).upsertMetric(any());
    }

    private List<EdgeManagement> captured(int times) {
        ArgumentCaptor<EdgeManagement> captor = ArgumentCaptor.forClass(EdgeManagement.class);
        verify(edges, times(times)).upsertMetric(captor.capture());
        return captor.getAllValues();
    }

    private NetworkMetricDto report(String source, String target, Long bps, Double latency) {
        return NetworkMetricDto.builder().sourceNode(source).targetNode(target)
                .bandwidthBps(bps).latencyMs(latency).build();
    }
}
