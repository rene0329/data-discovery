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
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NetworkTopologyService {
    private final EdgeManagementMapper edges;
    private final NodeManagementMapper nodes;
    private final NodeAvailabilityService availability;
    private final long staleAfterSeconds;

    public NetworkTopologyService(EdgeManagementMapper edges, NodeManagementMapper nodes,
                                  NodeAvailabilityService availability,
                                  @Value("${app.network-topology.stale-after-seconds:93600}") long staleAfterSeconds) {
        this.edges = edges;
        this.nodes = nodes;
        this.availability = availability;
        this.staleAfterSeconds = Math.max(1, staleAfterSeconds);
    }

    /**
     * Links are derived, not stored: full mesh inside each site, plus one link from each
     * other site's gateway to the hub site's hub node. Unknown, failed and stale links stay
     * visible without being treated as usable.
     */
    public List<EdgeManagement> links() {
        Map<String, EdgeManagement> measured = new HashMap<>();
        for (EdgeManagement metric : edges.selectAllMetrics()) {
            measured.put(pairKey(metric.getSourceId(), metric.getTargetId()), metric);
        }
        Instant cutoff = Instant.now().minusSeconds(staleAfterSeconds);
        List<EdgeManagement> result = new ArrayList<>();
        for (int[] pair : derivePairs(nodes.selectAllNodes())) {
            EdgeManagement metric = measured.get(pairKey(pair[0], pair[1]));
            String status = metric == null ? "UNKNOWN" : metric.getStatus();
            if (metric != null && ("active".equalsIgnoreCase(status) || "UP".equalsIgnoreCase(status))) {
                if (metric.getMeasurementTime() == null) status = "UNKNOWN";
                else if (metric.getMeasurementTime().toInstant().isBefore(cutoff)) status = "STALE";
            }
            result.add(EdgeManagement.builder().edgeId(result.size() + 1)
                    .sourceId(pair[0]).targetId(pair[1])
                    .bandwidth(metric == null ? null : metric.getBandwidth())
                    .latency(metric == null ? null : metric.getLatency())
                    .status(status).measurementTime(metric == null ? null : metric.getMeasurementTime()).build());
        }
        return result;
    }

    static final String HUB_SITE = "center";
    private static final Pattern REGIONAL_NODE = Pattern.compile("^cluster-([a-z]+)-\\d+$");
    private static final Map<String, String> REGION_SITES;
    static {
        Map<String, String> sites = new HashMap<>();
        sites.put("hz", "hangzhou");
        sites.put("sh", "shanghai");
        sites.put("bj", "beijing");
        sites.put("sz", "shenzhen");
        REGION_SITES = Collections.unmodifiableMap(sites);
    }

    /** "cluster-REGION-N" belongs to that region's site; any other name belongs to the hub site. */
    static String siteOf(String nodeName) {
        if (nodeName == null) return HUB_SITE;
        Matcher matcher = REGIONAL_NODE.matcher(nodeName.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) return HUB_SITE;
        return REGION_SITES.getOrDefault(matcher.group(1), matcher.group(1));
    }

    static List<int[]> derivePairs(List<NodeManagement> all) {
        Map<String, List<NodeManagement>> bySite = new TreeMap<>();
        for (NodeManagement node : all) {
            if (node.getNodeId() == null || node.getDeletedAt() != null) continue;
            bySite.computeIfAbsent(siteOf(node.getNodeName()), key -> new ArrayList<>()).add(node);
        }
        bySite.values().forEach(members -> members.sort(Comparator.comparing(NodeManagement::getNodeId)));
        Set<String> seen = new HashSet<>();
        List<int[]> pairs = new ArrayList<>();
        for (List<NodeManagement> members : bySite.values()) {
            for (int i = 0; i < members.size(); i++) {
                for (int j = i + 1; j < members.size(); j++) {
                    addPair(pairs, seen, members.get(i).getNodeId(), members.get(j).getNodeId());
                }
            }
        }
        String hubSite = bySite.containsKey(HUB_SITE) ? HUB_SITE : bySite.entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getValue().size()))
                .map(Map.Entry::getKey).orElse(null);
        if (hubSite == null) return pairs;
        NodeManagement hub = preferred(bySite.get(hubSite), "compute-storage", "compute", "storage");
        for (Map.Entry<String, List<NodeManagement>> site : bySite.entrySet()) {
            if (site.getKey().equals(hubSite)) continue;
            NodeManagement gateway = preferred(site.getValue(), "compute", "compute-storage", "storage");
            addPair(pairs, seen, gateway.getNodeId(), hub.getNodeId());
        }
        pairs.sort(Comparator.<int[]>comparingInt(pair -> pair[0]).thenComparingInt(pair -> pair[1]));
        return pairs;
    }

    /** First member (by node id) with the earliest-listed role; otherwise the lowest node id. */
    private static NodeManagement preferred(List<NodeManagement> members, String... roles) {
        for (String role : roles) {
            for (NodeManagement member : members) {
                if (role.equalsIgnoreCase(member.getType())) return member;
            }
        }
        return members.get(0);
    }

    private static void addPair(List<int[]> pairs, Set<String> seen, int a, int b) {
        if (a == b) return;
        int low = Math.min(a, b);
        int high = Math.max(a, b);
        if (seen.add(pairKey(low, high))) pairs.add(new int[]{low, high});
    }

    private static String pairKey(Integer a, Integer b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
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
