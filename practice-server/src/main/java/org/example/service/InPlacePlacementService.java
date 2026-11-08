package org.example.service;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.mapper.NodeManagementMapper;
import org.example.service.NetworkTopologyService.NetworkPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Multi-source nearest placement for IN_PLACE jobs. All usable replica nodes are zero-cost
 * sources; accumulated measured latency is the transmission cost. Site and central-node labels
 * do not change candidate priority. Equal-cost targets are ordered by node id for reproducibility.
 * Admission checks the current job demand and reserves capacity before the next job is considered.
 */
@Service
public class InPlacePlacementService {
    /** Describes the selected source/target relationship, not a scheduling priority. */
    public enum Tier { LOCAL, SAME_SITE, NEAREST }

    private final NodeManagementMapper nodeMapper;
    private final DatasetReplicaAvailabilityService replicaAvailabilityService;
    private final NodeAvailabilityService nodeAvailabilityService;
    private final NetworkTopologyService networkTopologyService;
    private final NodeResourceService nodeResourceService;

    public InPlacePlacementService(NodeManagementMapper nodeMapper,
                                   DatasetReplicaAvailabilityService replicaAvailabilityService,
                                   NodeAvailabilityService nodeAvailabilityService,
                                   NetworkTopologyService networkTopologyService) {
        this(nodeMapper, replicaAvailabilityService, nodeAvailabilityService, networkTopologyService, null, null);
    }

    /** Central-node arguments remain compatible with existing callers; all targets compete equally. */
    public InPlacePlacementService(NodeManagementMapper nodeMapper,
                                   DatasetReplicaAvailabilityService replicaAvailabilityService,
                                   NodeAvailabilityService nodeAvailabilityService,
                                   NetworkTopologyService networkTopologyService,
                                   String centralNodeName, String centralNodeIp) {
        this(nodeMapper, replicaAvailabilityService, nodeAvailabilityService, networkTopologyService,
                centralNodeName, centralNodeIp, null);
    }

    @Autowired
    public InPlacePlacementService(NodeManagementMapper nodeMapper,
                                   DatasetReplicaAvailabilityService replicaAvailabilityService,
                                   NodeAvailabilityService nodeAvailabilityService,
                                   NetworkTopologyService networkTopologyService,
                                   @Value("${dispatch.central-node.name:}") String centralNodeName,
                                   @Value("${dispatch.central-node.ip:}") String centralNodeIp,
                                   NodeResourceService nodeResourceService) {
        this.nodeResourceService = nodeResourceService;
        this.nodeMapper = nodeMapper;
        this.replicaAvailabilityService = replicaAvailabilityService;
        this.nodeAvailabilityService = nodeAvailabilityService;
        this.networkTopologyService = networkTopologyService;
    }

    public List<NodeManagement> schedulableComputeNodes() {
        return schedulable(nodeMapper.getComputeCapableNodes());
    }

    private List<NodeManagement> schedulable(List<NodeManagement> nodes) {
        return nodes == null ? Collections.emptyList() : nodes.stream()
                .filter(nodeAvailabilityService::isSchedulable).collect(Collectors.toList());
    }

    public Placement place(List<DatasetReplica> replicas) {
        return place(replicas, schedulableComputeNodes());
    }

    public Placement place(List<DatasetReplica> replicas, List<NodeManagement> computeNodes) {
        return place(replicas, computeNodes, null, null);
    }

    /** One nearest source per reachable compute node, ordered by cumulative transmission cost.
     * Enumerate all targets so the coordinator can log shortages and distinguish temporary
     * contention from a request exceeding every node's total capacity, then admit the first fit.
     */
    public List<Placement> candidates(List<DatasetReplica> replicas, List<NodeManagement> computeNodes) {
        return orderedCandidates(usableSources(replicas, schedulable(computeNodes), new ArrayList<>(), null),
                schedulable(computeNodes));
    }

    private List<Candidate> usableSources(List<DatasetReplica> replicas, List<NodeManagement> computeNodes,
                                         List<String> reasons, DecisionTrace trace) {
        List<Candidate> usable = new ArrayList<>();
        for (DatasetReplica replica : replicas == null ? Collections.<DatasetReplica>emptyList() : replicas) {
            NodeManagement source = nodeMapper.getNodeById(replica.getNodeId());
            if (source == null) source = computeNodes.stream()
                    .filter(node -> Objects.equals(node.getNodeId(), replica.getNodeId())).findFirst().orElse(null);
            ReplicaAvailability availability = replicaAvailabilityService.evaluate(replica);
            String reason = source == null ? "副本所在节点不存在"
                    : availability.isUsable() ? "副本通过可用性检查" : availability.getReason();
            if (trace != null) trace.add("REPLICA", replica.getReplicaId(), source, null, null, null,
                    source != null && availability.isUsable() ? "USABLE" : "REJECTED", reason);
            if (source != null && availability.isUsable()) usable.add(new Candidate(replica, source));
            else reasons.add(label(source, replica.getNodeId()) + ": " + reason);
        }
        usable.sort(Comparator.comparing((Candidate source) -> source.replica.getReplicaId(),
                Comparator.nullsLast(Comparator.naturalOrder())));
        return usable;
    }

    private List<Placement> orderedCandidates(List<Candidate> sources, List<NodeManagement> computeNodes) {
        if (sources.isEmpty() || computeNodes.isEmpty()) return Collections.emptyList();
        Map<Integer, Candidate> bySource = new LinkedHashMap<>();
        for (Candidate source : sources) bySource.putIfAbsent(source.node.getNodeId(), source);
        Map<Integer, NetworkPath> paths = networkTopologyService.pathsFromSources(bySource.keySet());
        List<Placement> result = new ArrayList<>();
        for (NodeManagement target : computeNodes) {
            // Local reads need no measured link. Multi-source roots always have cost zero.
            NetworkPath path = bySource.containsKey(target.getNodeId())
                    ? new NetworkPath(Collections.singletonList(target.getNodeId()), 0.0, Long.MAX_VALUE)
                    : paths.get(target.getNodeId());
            if (path == null) continue;
            Candidate source = bySource.get(path.getNodeIds().get(0));
            if (source == null) continue;
            Tier tier = tier(source.node, target);
            DecisionTrace trace = new DecisionTrace(null, null);
            trace.add("SELECTED", source.replica.getReplicaId(), source.node, target, tier, path, "SELECTED",
                    "多源最短路径按累计时延升序选择候选；实际资源由统一调度协调器检查");
            result.add(new Placement(source.replica, source.node, target, tier, path,
                    Collections.emptyList(), null, trace.events));
        }
        result.sort(Comparator.comparingDouble((Placement option) -> option.path.getLatencyMs())
                .thenComparing(option -> option.computeNode.getNodeId())
                .thenComparing(option -> NodeResourceLedger.key(option.computeNode)));
        return Collections.unmodifiableList(result);
    }

    public NodeResourceLedger resourceLedger(List<NodeManagement> computeNodes) {
        return nodeResourceService == null ? new NodeResourceLedger() : nodeResourceService.snapshot(computeNodes);
    }

    /** Legacy/preflight placement shares candidate ordering with durable resource admission. */
    public Placement place(List<DatasetReplica> replicas, List<NodeManagement> schedulableComputeNodes,
                           JobResourceDemand demand, NodeResourceLedger ledger) {
        List<NodeManagement> computeNodes = schedulable(schedulableComputeNodes);
        List<String> reasons = new ArrayList<>();
        DecisionTrace trace = new DecisionTrace(demand, ledger);
        trace.add("STARTED", null, null, null, null, null, "STARTED",
                "以所有可用副本为源执行多源最短路径搜索，按累计时延升序选择资源满足当前作业需求的节点；同代价按节点编号升序");
        for (NodeManagement compute : computeNodes) trace.add("NODE", null, null, compute, null, null,
                "SCHEDULABLE", "当前可用于调度的计算节点；剩余资源扣除 Pod 申请量及本轮资源预留");
        List<Candidate> sources = usableSources(replicas, computeNodes, reasons, trace);
        // Preserve every source-to-target path for diagnostics, even when selection can stop early.
        for (Candidate source : sources) {
            Map<Integer, NetworkPath> paths = networkTopologyService.pathsFrom(source.node.getNodeId());
            for (NodeManagement target : computeNodes) {
                NetworkPath path = Objects.equals(source.node.getNodeId(), target.getNodeId())
                        ? new NetworkPath(Collections.singletonList(target.getNodeId()), 0.0, Long.MAX_VALUE)
                        : paths.get(target.getNodeId());
                trace.add("PATH", source.replica.getReplicaId(), source.node, target, tier(source.node, target), path,
                        path == null ? "UNAVAILABLE" : "RECORDED",
                        path == null ? "逻辑网络拓扑中没有有效测量路径" : "记录源节点到计算节点的累计路径时延");
            }
        }
        Set<String> shortages = new LinkedHashSet<>();
        for (Placement option : orderedCandidates(sources, computeNodes)) {
            boolean fits = ledger == null || demand == null || ledger.fits(option.computeNode, demand);
            String reason = !fits ? ledger.shortage(resourceKey(option.computeNode), demand)
                    : ledger == null || demand == null ? "未执行资源容量检查" : "剩余资源满足当前作业需求";
            trace.add("CANDIDATE", option.replica.getReplicaId(), option.replicaNode, option.computeNode,
                    option.tier, option.path, fits ? "ELIGIBLE" : "REJECTED", reason);
            if (!fits) { shortages.add(reason); continue; }
            String note = shortages.isEmpty() ? null : String.join("；", shortages);
            trace.add("SELECTED", option.replica.getReplicaId(), option.replicaNode, option.computeNode,
                    option.tier, option.path, "SELECTED", "按最小累计时延选择首个资源满足要求的计算节点");
            if (ledger != null && demand != null) ledger.reserve(option.computeNode, demand);
            return new Placement(option.replica, option.replicaNode, option.computeNode, option.tier, option.path,
                    reasons, note, trace.events);
        }
        if (!shortages.isEmpty()) reasons.add("no compute node has enough free resources: " + String.join("；", shortages));
        else for (Candidate source : sources)
            reasons.add(label(source.node, source.node.getNodeId()) + ": no reachable compute node with a measured path");
        trace.add("FAILED", null, null, null, null, null, "FAILED", String.join("; ", reasons));
        return new Placement(null, null, null, null, null, reasons, null, trace.events);
    }

    private static Tier tier(NodeManagement source, NodeManagement target) {
        if (Objects.equals(source.getNodeId(), target.getNodeId())) return Tier.LOCAL;
        return source.getSiteCode() != null && source.getSiteCode().equals(target.getSiteCode())
                ? Tier.SAME_SITE : Tier.NEAREST;
    }

    private static String label(NodeManagement node, Integer nodeId) {
        return node == null || node.getNodeName() == null ? "node " + nodeId : node.getNodeName();
    }

    private static String resourceKey(NodeManagement node) { return NodeResourceLedger.key(node); }

    /** Captured during the actual traversal; preflight callers do not emit or persist it. */
    private static final class DecisionTrace {
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final JobResourceDemand demand;
        private final NodeResourceLedger ledger;

        DecisionTrace(JobResourceDemand demand, NodeResourceLedger ledger) {
            this.demand = demand;
            this.ledger = ledger;
        }

        void add(String step, Long replicaId, NodeManagement source, NodeManagement target,
                 Tier tier, NetworkPath path, String outcome, String reason) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "PLACEMENT_" + step);
            event.put("replicaId", replicaId);
            event.put("sourceNode", source == null ? null : source.getNodeName());
            event.put("sourceSite", source == null ? null : source.getSiteCode());
            event.put("targetNode", target == null ? null : target.getNodeName());
            event.put("targetSite", target == null ? null : target.getSiteCode());
            event.put("tier", tier);
            event.put("outcome", outcome);
            event.put("reason", reason);
            event.put("cpuRequested", demand == null ? null : demand.getCpu());
            event.put("memoryRequestedGi", demand == null ? null : demand.getMemoryGi());
            event.put("cpuFree", ledger == null || target == null ? null : ledger.availableCpu(resourceKey(target)));
            event.put("memoryFreeGi", ledger == null || target == null ? null : ledger.availableMemoryGi(resourceKey(target)));
            event.put("resourceCheck", ledger == null || demand == null || target == null
                    || ledger.availableCpu(resourceKey(target)) == null ? "UNAVAILABLE"
                    : ledger.fits(resourceKey(target), demand) ? "PASSED" : "INSUFFICIENT");
            event.put("latencyMs", path == null ? null : path.getLatencyMs());
            event.put("bandwidthMbps", path == null ? null : path.getBandwidthMbps());
            event.put("pathNodeIds", path == null ? null : path.getNodeIds());
            events.add(Collections.unmodifiableMap(event));
        }
    }

    private static final class Candidate {
        final DatasetReplica replica;
        final NodeManagement node;

        Candidate(DatasetReplica replica, NodeManagement node) {
            this.replica = replica;
            this.node = node;
        }
    }

    public static final class Placement {
        private final DatasetReplica replica;
        private final NodeManagement replicaNode;
        private final NodeManagement computeNode;
        private final Tier tier;
        private final NetworkPath path;
        private final List<String> rejectedReasons;
        private final String resourceNote;
        private final List<Map<String, Object>> decisionEvents;

        Placement(DatasetReplica replica, NodeManagement replicaNode, NodeManagement computeNode,
                  Tier tier, NetworkPath path, List<String> rejectedReasons, String resourceNote) {
            this(replica, replicaNode, computeNode, tier, path, rejectedReasons, resourceNote, Collections.emptyList());
        }

        Placement(DatasetReplica replica, NodeManagement replicaNode, NodeManagement computeNode,
                  Tier tier, NetworkPath path, List<String> rejectedReasons, String resourceNote,
                  List<Map<String, Object>> decisionEvents) {
            this.resourceNote = resourceNote;
            this.decisionEvents = Collections.unmodifiableList(new ArrayList<>(decisionEvents));
            this.replica = replica;
            this.replicaNode = replicaNode;
            this.computeNode = computeNode;
            this.tier = tier;
            this.path = path;
            this.rejectedReasons = Collections.unmodifiableList(new ArrayList<>(rejectedReasons));
        }

        public boolean isFound() { return replica != null; }
        public DatasetReplica getReplica() { return replica; }
        public NodeManagement getReplicaNode() { return replicaNode; }
        public NodeManagement getComputeNode() { return computeNode; }
        public Tier getTier() { return tier; }
        /** Measured shortest path; local reads have a singleton zero-cost path. */
        public NetworkPath getPath() { return path; }
        /** Why replicas were not usable for placement; complete only when nothing was found. */
        public List<String> getRejectedReasons() { return rejectedReasons; }
        /** Better compute nodes skipped for lack of free resources; null when the first choice fit. */
        public String getResourceNote() { return resourceNote; }
        public List<Map<String, Object>> getDecisionEvents() { return decisionEvents; }
    }
}
