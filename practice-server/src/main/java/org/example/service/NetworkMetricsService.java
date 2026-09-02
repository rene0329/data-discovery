package org.example.service;

import org.example.dto.NetworkMetricDto;
import org.example.dto.NetworkEdgeDto;
import org.example.entity.EdgeManagement;
import org.example.entity.NodeManagement;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class NetworkMetricsService {
    private final EdgeManagementMapper edges;
    private final NodeManagementMapper nodes;
    private final NetworkTopologyService topology;

    public NetworkMetricsService(EdgeManagementMapper edges, NodeManagementMapper nodes,
                                 NetworkTopologyService topology) {
        this.edges = edges;
        this.nodes = nodes;
        this.topology = topology;
    }

    @Transactional
    public void processNetworkMetrics(List<NetworkMetricDto> reports) {
        if (reports == null) return;
        for (NetworkMetricDto report : reports) saveMetrics(report);
    }

    @Transactional
    public void saveMetrics(NetworkMetricDto report) {
        if (report == null) return;
        NodeManagement source = nodes.getNodeByName(report.getSourceNode());
        NodeManagement target = nodes.getNodeByName(report.getTargetNode());
        if (source == null || target == null || source.getNodeId().equals(target.getNodeId())) return;
        // The policy row must already exist: reachability never creates a logical link.
        EdgeManagement edge = edges.findBySourceAndTargetNode(
                Math.min(source.getNodeId(), target.getNodeId()),
                Math.max(source.getNodeId(), target.getNodeId()));
        if (edge == null) return;
        if (report.getLatencyMs() != null && Double.isFinite(report.getLatencyMs())
                && report.getLatencyMs() >= 0 && report.getBandwidthBps() != null
                && report.getBandwidthBps() > 0) {
            edge.setLatency(report.getLatencyMs());
            edge.setBandwidth(Math.max(1L, Math.round(report.getBandwidthBps() / 1_000_000.0)));
            edge.setStatus("active");
        } else {
            // Retain the last metrics for display, but exclude failed/incomplete probes from routing.
            edge.setStatus("inactive");
        }
        edges.updateEdge(edge);
    }

    public List<NetworkEdgeDto> getAllEdgesWithNodeNames() {
        Map<Integer, String> names = nodes.selectAllNodes().stream()
                .collect(Collectors.toMap(NodeManagement::getNodeId, NodeManagement::getNodeName));
        return topology.links().stream().map(edge -> NetworkEdgeDto.builder()
                .edgeId(edge.getEdgeId()).sourceId(edge.getSourceId()).targetId(edge.getTargetId())
                .sourceNodeName(names.get(edge.getSourceId())).targetNodeName(names.get(edge.getTargetId()))
                .bandwidth(edge.getBandwidth()).latency(edge.getLatency()).status(edge.getStatus())
                .measurementTime(edge.getMeasurementTime() == null ? null : edge.getMeasurementTime().getTime())
                .build()).collect(Collectors.toList());
    }
}
