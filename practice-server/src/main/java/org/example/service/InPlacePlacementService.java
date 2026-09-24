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
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Without a configured central node every compute node is treated alike. */
    public InPlacePlacementService(NodeManagementMapper nodeMapper,
                                   DatasetReplicaAvailabilityService replicaAvailabilityService,
                                   NodeAvailabilityService nodeAvailabilityService,
                                   NetworkTopologyService networkTopologyService) {
        this(nodeMapper, replicaAvailabilityService, nodeAvailabilityService, networkTopologyService, null, null);
    }

    @Autowired
    public InPlacePlacementService(NodeManagementMapper nodeMapper,
                                   DatasetReplicaAvailabilityService replicaAvailabilityService,
                                   NodeAvailabilityService nodeAvailabilityService,
                                   NetworkTopologyService networkTopologyService,
                                   @Value("${dispatch.central-node.name:}") String centralNodeName,
                                   @Value("${dispatch.central-node.ip:}") String centralNodeIp) {
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

        for (Candidate candidate : usable) {
            for (NodeManagement compute : computeNodes) {
                if (Objects.equals(compute.getNodeId(), candidate.node.getNodeId())) {
                    return new Placement(candidate.replica, candidate.node, compute, Tier.LOCAL, null, reasons);
                }
            }
        }
        List<NodeManagement> awayFromCentral = computeNodes.stream()
                .filter(compute -> !isCentral(compute)).collect(Collectors.toList());
        if (awayFromCentral.size() < computeNodes.size()) {
            Placement distributed = nearby(usable, awayFromCentral, reasons, new ArrayList<>());
            if (distributed != null) return distributed;
        }
        List<String> unreachable = new ArrayList<>();
        Placement any = nearby(usable, computeNodes, reasons, unreachable);
        if (any != null) return any;
        reasons.addAll(unreachable);
        return new Placement(null, null, null, null, null, reasons);
    }

    /** Tiers 2 and 3 over the given compute nodes; null when none is reachable. */
    private Placement nearby(List<Candidate> usable, List<NodeManagement> computeNodes,
                             List<String> reasons, List<String> unreachable) {
        for (Candidate candidate : usable) {
            String siteCode = candidate.node.getSiteCode();
            if (siteCode == null) continue;
            for (NodeManagement compute : computeNodes) {
                if (siteCode.equals(compute.getSiteCode())) {
                    return new Placement(candidate.replica, candidate.node, compute, Tier.SAME_SITE, null, reasons);
                }
            }
        }

        Comparator<Placement> nearest = Comparator
                .comparingDouble((Placement p) -> p.getPath().getLatencyMs())
                .thenComparing(Comparator.comparingLong((Placement p) -> p.getPath().getBandwidthMbps()).reversed())
                .thenComparing(p -> p.getComputeNode().getNodeId())
                .thenComparing(p -> String.valueOf(p.getComputeNode().getNodeName()));
        Placement best = null;
        for (Candidate candidate : usable) {
            Map<Integer, NetworkPath> paths = networkTopologyService.pathsFrom(candidate.node.getNodeId());
            Placement local = null;
            for (NodeManagement compute : computeNodes) {
                NetworkPath path = paths.get(compute.getNodeId());
                if (path == null) continue;
                Placement option = new Placement(candidate.replica, candidate.node, compute, Tier.NEAREST, path, reasons);
                if (local == null || nearest.compare(option, local) < 0) local = option;
            }
            String nodeLabel = label(candidate.node, candidate.node.getNodeId());
            if (local == null) {
                String site = candidate.node.getSiteCode() == null ? "node has no site_code"
                        : "no available compute node in site '" + candidate.node.getSiteCode() + "'";
                unreachable.add(nodeLabel + ": " + site + ", and no reachable compute node from " + nodeLabel);
            } else if (best == null || nearest.compare(local, best) < 0) {
                best = local;
            }
        }
        return best;
    }

    private boolean isCentral(NodeManagement node) {
        return (!centralNodeName.isEmpty() && centralNodeName.equals(node.getNodeName()))
                || (!centralNodeIp.isEmpty() && centralNodeIp.equals(node.getInternalIp()));
    }

    private static String label(NodeManagement node, Integer nodeId) {
        return node == null || node.getNodeName() == null ? "node " + nodeId : node.getNodeName();
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

        Placement(DatasetReplica replica, NodeManagement replicaNode, NodeManagement computeNode,
                  Tier tier, NetworkPath path, List<String> rejectedReasons) {
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
    }
}
