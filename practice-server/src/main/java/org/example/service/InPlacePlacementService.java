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
 * Chooses where a distributed (IN_PLACE) run of one dataset executes. Task preflight and the
 * orchestrator both call {@link #place}, so a task that passes preflight runs exactly where
 * preflight said it could. Priority:
 * <ol>
 *   <li>a usable replica already on a schedulable compute node runs there (zero hops);</li>
 *   <li>else a usable replica whose site_code has a schedulable compute node runs on that
 *       same-site compute node;</li>
 *   <li>else, over every usable replica, the schedulable compute node nearest to the replica's
 *       node on the logical network topology: lowest total latency, then highest bottleneck
 *       bandwidth, then lowest node id. This crosses site boundaries (and may pick the central
 *       node); it also covers replicas whose node has no site_code.</li>
 * </ol>
 * Tiers 2 and 3 skip the central node, which is where CENTRALIZED runs execute: a distributed run
 * lands there only when its replica is on it, or when no other compute node is reachable.
 * Given a resource ledger, a compute node whose free CPU or memory cannot hold the Job is skipped
 * and the next node in the same order is used (the second-best node); the Job's request is then
 * reserved in the ledger, so later datasets of the same task see it.
 * Only when no usable replica can reach any schedulable compute node is there no placement.
 */
@Service
public class InPlacePlacementService {
    public enum Tier { LOCAL, SAME_SITE, NEAREST }

    private final NodeManagementMapper nodeMapper;
    private final DatasetReplicaAvailabilityService replicaAvailabilityService;
    private final NodeAvailabilityService nodeAvailabilityService;
    private final NetworkTopologyService networkTopologyService;
    private final String centralNodeName;
    private final String centralNodeIp;
    private final NodeResourceService nodeResourceService;

    /** Without a configured central node every compute node is treated alike. */
    public InPlacePlacementService(NodeManagementMapper nodeMapper,
                                   DatasetReplicaAvailabilityService replicaAvailabilityService,
                                   NodeAvailabilityService nodeAvailabilityService,
                                   NetworkTopologyService networkTopologyService) {
        this(nodeMapper, replicaAvailabilityService, nodeAvailabilityService, networkTopologyService, null, null);
    }

    /** Without a resource service no ledger is available and resources are not checked. */
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
        this.centralNodeName = centralNodeName == null ? "" : centralNodeName.trim();
        this.centralNodeIp = centralNodeIp == null ? "" : centralNodeIp.trim();
    }

    /** Compute-capable nodes that are currently schedulable, in mapper order. */
    public List<NodeManagement> schedulableComputeNodes() {
        List<NodeManagement> computeNodes = nodeMapper.getComputeCapableNodes();
        return computeNodes == null ? Collections.emptyList() : computeNodes.stream()
                .filter(nodeAvailabilityService::isSchedulable).collect(Collectors.toList());
    }

    public Placement place(List<DatasetReplica> replicas) {
        return place(replicas, schedulableComputeNodes());
    }

    public Placement place(List<DatasetReplica> replicas, List<NodeManagement> schedulableComputeNodes) {
        return place(replicas, schedulableComputeNodes, null, null);
    }

    /** Free resources of the given compute nodes for one placement pass; null when they cannot be read. */
    public NodeResourceLedger resourceLedger(List<NodeManagement> computeNodes) {
        return nodeResourceService == null ? null : nodeResourceService.snapshot(computeNodes);
    }

    /** With a null ledger (or demand) resources are not checked, as before. */
    public Placement place(List<DatasetReplica> replicas, List<NodeManagement> schedulableComputeNodes,
                           JobResourceDemand demand, NodeResourceLedger ledger) {
        List<NodeManagement> computeNodes = schedulableComputeNodes == null
                ? Collections.emptyList() : schedulableComputeNodes;
        List<String> reasons = new ArrayList<>();
        List<Candidate> usable = new ArrayList<>();
        DecisionTrace trace = new DecisionTrace(demand, ledger);
        trace.add("STARTED", null, null, null, null, null, "STARTED",
                "副本所在计算节点优先，其次同站点；再按路径时延升序、瓶颈带宽降序、节点编号升序选择；同站点按候选顺序选择；优先避开中心节点");
        for (NodeManagement compute : computeNodes) {
            trace.add("NODE", null, null, compute, null, null, "SCHEDULABLE",
                    "当前可用于调度的计算节点；空闲资源为可分配资源减去 Pod 已申请资源");
        }
        for (DatasetReplica replica : replicas == null ? Collections.<DatasetReplica>emptyList() : replicas) {
            NodeManagement node = nodeMapper.getNodeById(replica.getNodeId());
            if (node == null) {
                node = computeNodes.stream().filter(compute -> Objects.equals(
                        compute.getNodeId(), replica.getNodeId())).findFirst().orElse(null);
            }
            ReplicaAvailability availability = replicaAvailabilityService.evaluate(replica);
            trace.add("REPLICA", replica.getReplicaId(), node, null, null, null,
                    availability.isUsable() && node != null ? "USABLE" : "REJECTED",
                    node == null ? "副本所在节点不存在"
                            : availability.isUsable() ? "副本通过可用性检查" : availability.getReason());
            if (!availability.isUsable()) {
                reasons.add(label(node, replica.getNodeId()) + ": " + availability.getReason());
            } else if (node == null) {
                reasons.add(label(null, replica.getNodeId()) + ": replica node not found");
            } else {
                usable.add(new Candidate(replica, node));
            }
        }
        Fit fit = new Fit(demand, ledger, trace);
        trace.add("STAGE", null, null, null, Tier.LOCAL, null, "STARTED", "检查副本所在节点是否具备计算能力且资源满足请求");
        Placement placement = locate(usable, computeNodes, reasons, fit);
        if (placement.isFound()) {
            String reason = placement.getTier() == Tier.LOCAL ? "数据副本所在计算节点优先"
                    : placement.getTier() == Tier.SAME_SITE ? "按候选节点顺序选择副本同站点计算节点"
                    : "在可达候选节点中，按路径时延升序、瓶颈带宽降序、节点编号升序选择";
            if (isCentral(placement.getComputeNode()) && placement.getTier() != Tier.LOCAL) {
                reason += "；其他计算节点无可用放置，回退到中心节点";
            }
            if (placement.getResourceNote() != null) reason += "；" + placement.getResourceNote();
            trace.add("SELECTED", placement.getReplica().getReplicaId(), placement.getReplicaNode(),
                    placement.getComputeNode(), placement.getTier(), placement.getPath(), "SELECTED", reason);
            fit.reserve(placement.getComputeNode());
        } else {
            trace.add("FAILED", null, null, null, null, null, "FAILED", String.join("; ", reasons));
        }
        return new Placement(placement.getReplica(), placement.getReplicaNode(), placement.getComputeNode(),
                placement.getTier(), placement.getPath(), placement.getRejectedReasons(),
                placement.getResourceNote(), trace.events);
    }

    private Placement locate(List<Candidate> usable, List<NodeManagement> computeNodes,
                             List<String> reasons, Fit fit) {
        for (Candidate candidate : usable) {
            for (NodeManagement compute : computeNodes) {
                if (Objects.equals(compute.getNodeId(), candidate.node.getNodeId())
                        && fit.accepts(candidate, compute, Tier.LOCAL, null, true)) {
                    return fit.placement(candidate.replica, candidate.node, compute, Tier.LOCAL, null, reasons);
                }
            }
        }
        List<NodeManagement> awayFromCentral = computeNodes.stream()
                .filter(compute -> !isCentral(compute)).collect(Collectors.toList());
        if (awayFromCentral.size() < computeNodes.size()) {
            fit.trace.add("STAGE", null, null, null, null, null, "STARTED", "本地放置不可用；优先检查非中心计算节点");
            Placement distributed = nearby(usable, awayFromCentral, reasons, new ArrayList<>(), fit);
            if (distributed != null) return distributed;
            fit.trace.add("STAGE", null, null, null, null, null, "FALLBACK", "非中心计算节点无可用放置；将中心节点纳入候选范围");
        }
        List<String> unreachable = new ArrayList<>();
        Placement any = nearby(usable, computeNodes, reasons, unreachable, fit);
        if (any != null) return any;
        reasons.addAll(unreachable);
        if (fit.note() != null) reasons.add("no compute node has enough free resources: " + fit.note());
        return new Placement(null, null, null, null, null, reasons, null);
    }

    /** Tiers 2 and 3 over the given compute nodes; null when none is reachable (and fits). */
    private Placement nearby(List<Candidate> usable, List<NodeManagement> computeNodes,
                             List<String> reasons, List<String> unreachable, Fit fit) {
        fit.trace.add("STAGE", null, null, null, Tier.SAME_SITE, null, "STARTED", "检查副本同站点的计算节点，按候选节点顺序选择");
        for (Candidate candidate : usable) {
            String siteCode = candidate.node.getSiteCode();
            if (siteCode == null) continue;
            for (NodeManagement compute : computeNodes) {
                if (siteCode.equals(compute.getSiteCode())) {
                    // Record available path measurements without changing same-site priority.
                    NetworkPath path = networkTopologyService.pathsFrom(candidate.node.getNodeId())
                            .get(compute.getNodeId());
                    if (fit.accepts(candidate, compute, Tier.SAME_SITE, path, true)) {
                        return fit.placement(candidate.replica, candidate.node, compute, Tier.SAME_SITE, path, reasons);
                    }
                }
            }
        }

        fit.trace.add("STAGE", null, null, null, Tier.NEAREST, null, "STARTED", "同站点无可用放置；比较可达节点的路径时延和瓶颈带宽");
        Comparator<Placement> nearest = Comparator
                .comparingDouble((Placement p) -> p.getPath().getLatencyMs())
                .thenComparing(Comparator.comparingLong((Placement p) -> p.getPath().getBandwidthMbps()).reversed())
                .thenComparing(p -> p.getComputeNode().getNodeId())
                .thenComparing(p -> String.valueOf(p.getComputeNode().getNodeName()));
        Placement best = null;
        List<Placement> full = new ArrayList<>();
        for (Candidate candidate : usable) {
            Map<Integer, NetworkPath> paths = networkTopologyService.pathsFrom(candidate.node.getNodeId());
            Placement local = null;
            boolean reachable = false;
            for (NodeManagement compute : computeNodes) {
                NetworkPath path = paths.get(compute.getNodeId());
                if (path == null) {
                    fit.trace.add("CANDIDATE", candidate.replica.getReplicaId(), candidate.node, compute,
                            Tier.NEAREST, null, "REJECTED", "逻辑网络拓扑中没有可用路径");
                    continue;
                }
                reachable = true;
                Placement option = new Placement(candidate.replica, candidate.node, compute, Tier.NEAREST, path, reasons, null);
                if (!fit.accepts(candidate, compute, Tier.NEAREST, path, false)) {
                    full.add(option);
                    continue;
                }
                if (local == null || nearest.compare(option, local) < 0) local = option;
            }
            String nodeLabel = label(candidate.node, candidate.node.getNodeId());
            if (!reachable) {
                String site = candidate.node.getSiteCode() == null ? "node has no site_code"
                        : "no available compute node in site '" + candidate.node.getSiteCode() + "'";
                unreachable.add(nodeLabel + ": " + site + ", and no reachable compute node from " + nodeLabel);
            } else if (local != null && (best == null || nearest.compare(local, best) < 0)) {
                best = local;
            }
        }
        // Only nodes that would have won without the resource check explain the choice.
        for (Placement skipped : full) {
            if (best == null || nearest.compare(skipped, best) < 0) fit.accepts(skipped.getComputeNode());
        }
        return best == null ? null : fit.placement(best.getReplica(), best.getReplicaNode(),
                best.getComputeNode(), Tier.NEAREST, best.getPath(), reasons);
    }

    private boolean isCentral(NodeManagement node) {
        return (!centralNodeName.isEmpty() && centralNodeName.equals(node.getNodeName()))
                || (!centralNodeIp.isEmpty() && centralNodeIp.equals(node.getInternalIp()));
    }

    private static String label(NodeManagement node, Integer nodeId) {
        return node == null || node.getNodeName() == null ? "node " + nodeId : node.getNodeName();
    }

    /** The resource check of one placement; remembers the better nodes it had to skip. */
    private static final class Fit {
        private final JobResourceDemand demand;
        private final NodeResourceLedger ledger;
        private final Set<String> shortages = new LinkedHashSet<>();
        private final DecisionTrace trace;

        Fit(JobResourceDemand demand, NodeResourceLedger ledger, DecisionTrace trace) {
            this.demand = demand;
            this.ledger = ledger;
            this.trace = trace;
        }

        boolean fits(NodeManagement compute) {
            return ledger == null || demand == null || ledger.fits(compute.getNodeName(), demand);
        }

        /** Like {@link #fits}, but a node that does not fit is recorded as skipped. */
        boolean accepts(NodeManagement compute) {
            if (fits(compute)) return true;
            shortages.add(ledger.shortage(compute.getNodeName(), demand));
            return false;
        }

        boolean accepts(Candidate source, NodeManagement compute, Tier tier, NetworkPath path,
                        boolean rememberShortage) {
            boolean fits = fits(compute);
            String reason = !fits ? ledger.shortage(compute.getNodeName(), demand)
                    : ledger == null || demand == null || ledger.availableCpu(compute.getNodeName()) == null
                    ? "没有完整的资源快照，按现有策略继续选择" : "CPU 和内存空闲资源满足请求";
            trace.add("CANDIDATE", source.replica.getReplicaId(), source.node, compute, tier, path,
                    fits ? "ELIGIBLE" : "REJECTED", reason);
            if (!fits && rememberShortage) accepts(compute);
            return fits;
        }

        void reserve(NodeManagement compute) {
            if (ledger != null && demand != null) ledger.reserve(compute.getNodeName(), demand);
        }

        String note() {
            return shortages.isEmpty() ? null : String.join("；", shortages);
        }

        Placement placement(DatasetReplica replica, NodeManagement replicaNode, NodeManagement compute,
                            Tier tier, NetworkPath path, List<String> reasons) {
            return new Placement(replica, replicaNode, compute, tier, path, reasons, note());
        }
    }

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
            event.put("cpuFree", ledger == null || target == null ? null : ledger.availableCpu(target.getNodeName()));
            event.put("memoryFreeGi", ledger == null || target == null ? null : ledger.availableMemoryGi(target.getNodeName()));
            event.put("resourceCheck", ledger == null || demand == null || target == null
                    || ledger.availableCpu(target.getNodeName()) == null ? "UNAVAILABLE"
                    : ledger.fits(target.getNodeName(), demand) ? "PASSED" : "INSUFFICIENT");
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
        /** Network path for a NEAREST placement; null for LOCAL and SAME_SITE. */
        public NetworkPath getPath() { return path; }
        /** Why replicas were not usable for placement; complete only when nothing was found. */
        public List<String> getRejectedReasons() { return rejectedReasons; }
        /** Better compute nodes skipped for lack of free resources; null when the first choice fit. */
        public String getResourceNote() { return resourceNote; }
        public List<Map<String, Object>> getDecisionEvents() { return decisionEvents; }
    }
}
