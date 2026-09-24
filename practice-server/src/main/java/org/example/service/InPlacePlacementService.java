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
        for (DatasetReplica replica : replicas == null ? Collections.<DatasetReplica>emptyList() : replicas) {
            NodeManagement node = nodeMapper.getNodeById(replica.getNodeId());
            if (node == null) {
                node = computeNodes.stream().filter(compute -> Objects.equals(
                        compute.getNodeId(), replica.getNodeId())).findFirst().orElse(null);
            }
            ReplicaAvailability availability = replicaAvailabilityService.evaluate(replica);
            if (!availability.isUsable()) {
                reasons.add(label(node, replica.getNodeId()) + ": " + availability.getReason());
            } else if (node == null) {
                reasons.add(label(null, replica.getNodeId()) + ": replica node not found");
            } else {
                usable.add(new Candidate(replica, node));
            }
        }
        Fit fit = new Fit(demand, ledger);
        Placement placement = locate(usable, computeNodes, reasons, fit);
        if (placement.isFound()) fit.reserve(placement.getComputeNode());
        return placement;
    }

    private Placement locate(List<Candidate> usable, List<NodeManagement> computeNodes,
                             List<String> reasons, Fit fit) {
        for (Candidate candidate : usable) {
            for (NodeManagement compute : computeNodes) {
                if (Objects.equals(compute.getNodeId(), candidate.node.getNodeId()) && fit.accepts(compute)) {
                    return fit.placement(candidate.replica, candidate.node, compute, Tier.LOCAL, null, reasons);
                }
            }
        }
        List<NodeManagement> awayFromCentral = computeNodes.stream()
                .filter(compute -> !isCentral(compute)).collect(Collectors.toList());
        if (awayFromCentral.size() < computeNodes.size()) {
            Placement distributed = nearby(usable, awayFromCentral, reasons, new ArrayList<>(), fit);
            if (distributed != null) return distributed;
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
        for (Candidate candidate : usable) {
            String siteCode = candidate.node.getSiteCode();
            if (siteCode == null) continue;
            for (NodeManagement compute : computeNodes) {
                if (siteCode.equals(compute.getSiteCode()) && fit.accepts(compute)) {
                    return fit.placement(candidate.replica, candidate.node, compute, Tier.SAME_SITE, null, reasons);
                }
            }
        }

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
                if (path == null) continue;
                reachable = true;
                Placement option = new Placement(candidate.replica, candidate.node, compute, Tier.NEAREST, path, reasons, null);
                if (!fit.fits(compute)) {
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

        Fit(JobResourceDemand demand, NodeResourceLedger ledger) {
            this.demand = demand;
            this.ledger = ledger;
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

        Placement(DatasetReplica replica, NodeManagement replicaNode, NodeManagement computeNode,
                  Tier tier, NetworkPath path, List<String> rejectedReasons, String resourceNote) {
            this.resourceNote = resourceNote;
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
    }
}
