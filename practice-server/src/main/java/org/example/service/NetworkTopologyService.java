package org.example.service;

import org.example.entity.EdgeManagement;
import org.example.entity.NodeManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

@Service
public class NetworkTopologyService {
    private final EdgeManagementMapper edges;
    private final NodeManagementMapper nodes;
    private final NodeAvailabilityService availability;
    private final long staleAfterSeconds;

    public NetworkTopologyService(EdgeManagementMapper edges, NodeManagementMapper nodes,
                                  NodeAvailabilityService availability,
                                  @Value("${app.network-topology.stale-after-seconds:1800}") long staleAfterSeconds) {
        this.edges = edges;
        this.nodes = nodes;
        this.availability = availability;
        this.staleAfterSeconds = Math.max(1, staleAfterSeconds);
    }

    /** Keep unknown, failed and stale links visible, without treating them as usable. */
    public List<EdgeManagement> links() {
        Instant cutoff = Instant.now().minusSeconds(staleAfterSeconds);
        List<EdgeManagement> result = new ArrayList<>();
        for (EdgeManagement edge : edges.links()) {
            String status = edge.getStatus();
            if ("active".equalsIgnoreCase(status) || "UP".equalsIgnoreCase(status)) {
                if (edge.getMeasurementTime() == null) status = "UNKNOWN";
                else if (edge.getMeasurementTime().toInstant().isBefore(cutoff)) status = "STALE";
            }
            result.add(EdgeManagement.builder().edgeId(edge.getEdgeId())
                    .sourceId(edge.getSourceId()).targetId(edge.getTargetId())
                    .bandwidth(edge.getBandwidth()).latency(edge.getLatency())
                    .status(status).measurementTime(edge.getMeasurementTime()).build());
        }
        return result;
    }

    /** Minimum accumulated latency; bandwidth is the bottleneck along that same path. */
    public Map<Integer, NetworkPath> pathsFrom(Integer sourceId) {
        Set<Integer> available = new HashSet<>();
        for (NodeManagement node : nodes.selectAllNodes()) {
            if (availability.isSchedulable(node)) available.add(node.getNodeId());
        }
        if (!available.contains(sourceId)) return Collections.emptyMap();
        Map<Integer, List<EdgeManagement>> adjacency = new HashMap<>();
        for (EdgeManagement edge : links()) {
            if (!available.contains(edge.getSourceId()) || !available.contains(edge.getTargetId())
                    || !usable(edge)) continue;
            adjacency.computeIfAbsent(edge.getSourceId(), key -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.getTargetId(), key -> new ArrayList<>()).add(edge);
        }
        Comparator<NetworkPath> order = Comparator.comparingDouble(NetworkPath::getLatencyMs)
                .thenComparingInt(path -> path.getNodeIds().size())
                .thenComparing(path -> path.getNodeIds().get(path.getNodeIds().size() - 1));
        PriorityQueue<NetworkPath> pending = new PriorityQueue<>(order);
        Map<Integer, NetworkPath> best = new HashMap<>();
        NetworkPath start = new NetworkPath(Collections.singletonList(sourceId), 0.0, Long.MAX_VALUE);
        best.put(sourceId, start);
        pending.add(start);
        while (!pending.isEmpty()) {
            NetworkPath path = pending.poll();
            int current = path.getNodeIds().get(path.getNodeIds().size() - 1);
            if (best.get(current) != path) continue;
            for (EdgeManagement edge : adjacency.getOrDefault(current, Collections.emptyList())) {
                int peer = edge.getSourceId() == current ? edge.getTargetId() : edge.getSourceId();
                List<Integer> route = new ArrayList<>(path.getNodeIds());
                route.add(peer);
                NetworkPath next = new NetworkPath(Collections.unmodifiableList(route),
                        path.getLatencyMs() + edge.getLatency(),
                        Math.min(path.getBandwidthMbps(), edge.getBandwidth()));
                if (!best.containsKey(peer) || order.compare(next, best.get(peer)) < 0) {
                    best.put(peer, next);
                    pending.add(next);
                }
            }
        }
        return best;
    }

    public NetworkPath requirePath(Integer sourceId, Integer targetId) {
        NetworkPath path = pathsFrom(sourceId).get(targetId);
        if (path == null) {
            throw RegistrationException.conflict("No available logical topology path: " + sourceId + " -> " + targetId);
        }
        return path;
    }

    private boolean usable(EdgeManagement edge) {
        return ("active".equalsIgnoreCase(edge.getStatus()) || "UP".equalsIgnoreCase(edge.getStatus()))
                && edge.getLatency() != null && Double.isFinite(edge.getLatency()) && edge.getLatency() >= 0
                && edge.getBandwidth() != null && edge.getBandwidth() > 0;
    }

    @lombok.Value
    public static class NetworkPath {
        List<Integer> nodeIds;
        double latencyMs;
        long bandwidthMbps;
    }
}
