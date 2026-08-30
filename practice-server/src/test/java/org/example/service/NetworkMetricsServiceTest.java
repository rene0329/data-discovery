package org.example.service;

import org.example.dto.NetworkMetricDto;
import org.example.entity.EdgeManagement;
import org.example.entity.NodeManagement;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkMetricsServiceTest {
    private EdgeManagementMapper edges;
    private NodeManagementMapper nodes;
    private NetworkMetricsService service;

    @BeforeEach
    void setUp() {
        edges = mock(EdgeManagementMapper.class);
        nodes = mock(NodeManagementMapper.class);
        service = new NetworkMetricsService();
        ReflectionTestUtils.setField(service, "edgeManagementMapper", edges);
        ReflectionTestUtils.setField(service, "nodeManagementMapper", nodes);
        when(nodes.getNodeByName("z-node")).thenReturn(NodeManagement.builder().nodeId(4).nodeName("z-node").build());
        when(nodes.getNodeByName("a-node")).thenReturn(NodeManagement.builder().nodeId(1).nodeName("a-node").build());
    }

    @Test
    void normalizesUndirectedPairAndConvertsBpsToMbps() {
        service.saveMetrics(NetworkMetricDto.builder()
                .sourceNode("z-node").targetNode("a-node")
                .bandwidthBps(93_666_000L).latencyMs(33.7).build());

        verify(edges).insertEdge(org.mockito.ArgumentMatchers.argThat(edge ->
                edge.getSourceId() == 1 && edge.getTargetId() == 4
                        && edge.getBandwidth() == 94L
                        && edge.getLatency() == 33.7
                        && "active".equals(edge.getStatus())));
    }

    @Test
    void incompleteProbeDoesNotOverwriteKnownLink() {
        service.saveMetrics(NetworkMetricDto.builder()
                .sourceNode("z-node").targetNode("a-node")
                .latencyMs(33.7).build());

        verify(edges, never()).insertEdge(any(EdgeManagement.class));
        verify(edges, never()).updateEdge(any(EdgeManagement.class));
    }
}
