package org.example.service;

import org.example.entity.DataTransferRateLimitConfiguration;
import org.example.entity.NodeManagement;
import org.example.mapper.NodeManagementMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Comparator;
import java.util.Map;

/** Calculates a conservative per-transfer curl rate from recent topology measurements. */
@Service
public class AdaptiveTransferRateService {
    private static final BigDecimal TARGET_RANGE_POSITION = new BigDecimal("0.800");
    private static final BigDecimal BITS_PER_BYTE = new BigDecimal("8");
    private static final BigDecimal BYTES_PER_KIB = new BigDecimal("1024");
    private static final BigDecimal BITS_PER_MEGABIT = new BigDecimal("1000000");

    private final NodeManagementMapper nodes;
    private final NetworkTopologyService topology;

    public AdaptiveTransferRateService(NodeManagementMapper nodes, NetworkTopologyService topology) {
        this.nodes = nodes;
        this.topology = topology;
    }

    public Recommendation recommend(DataTransferRateLimitConfiguration configuration,
                                    String sourceNodeName, String targetNodeName) {
        return recommend(configuration, sourceNodeName, targetNodeName, null);
    }

    public Recommendation recommend(DataTransferRateLimitConfiguration configuration,
                                    String sourceNodeName, String targetNodeName, String comparisonNodeName) {
        if (configuration == null || !Boolean.TRUE.equals(configuration.getAdaptiveEnabled())) {
            return Recommendation.unavailable("adaptive mode is disabled");
        }
        if (sourceNodeName == null || targetNodeName == null) {
            return Recommendation.unavailable("source or target node is missing");
        }
        NodeManagement source = nodes.getNodeByName(sourceNodeName);
        NodeManagement target = nodes.getNodeByName(targetNodeName);
        NodeManagement central = nodes.getNodeByName(configuration.getAdaptiveCentralNode());
        if (source == null || target == null || central == null) {
            return Recommendation.unavailable("source, target or central node is not registered");
        }

        int maxAge = configuration.getMeasurementMaxAgeSeconds() == null
                ? 900 : configuration.getMeasurementMaxAgeSeconds();
        Map<Integer, NetworkTopologyService.NetworkPath> paths =
                topology.pathsFrom(source.getNodeId(), maxAge);
        NetworkTopologyService.NetworkPath targetPath = paths.get(target.getNodeId());
        NetworkTopologyService.NetworkPath centralPath = paths.get(central.getNodeId());
        if (!usable(targetPath) || !usable(centralPath)) {
            return Recommendation.unavailable("no fresh measured path to the target or central node");
        }

        // The central transfer needs a deterministic comparison path. Use the nearest
        // reachable non-central compute node, preferring the source's site, just as
        // placement does. A different actual target uses its own measured path.
        NodeManagement comparison = target;
        boolean centralized = target.getNodeId().equals(central.getNodeId());
        if (centralized && comparisonNodeName != null) {
            comparison = nodes.getNodeByName(comparisonNodeName);
        } else if (centralized) {
            List<NodeManagement> computeNodes = nodes.getComputeCapableNodes();
            comparison = computeNodes == null ? null : computeNodes.stream()
                    .filter(node -> node != null && node.getNodeId() != null
                            && !node.getNodeId().equals(central.getNodeId())
                            && usable(paths.get(node.getNodeId())))
                    .min(Comparator.<NodeManagement>comparingInt(node ->
                                    source.getSiteCode() != null && source.getSiteCode().equals(node.getSiteCode()) ? 0 : 1)
                            .thenComparingDouble(node -> paths.get(node.getNodeId()).getLatencyMs())
                            .thenComparing(Comparator.comparingLong((NodeManagement node) ->
                                    paths.get(node.getNodeId()).getBandwidthMbps()).reversed())
                            .thenComparing(NodeManagement::getNodeId))
                    .orElse(null);
        }
        if (comparison == null) {
            return Recommendation.unavailable("no fresh measured comparison path");
        }
        NetworkTopologyService.NetworkPath comparisonPath = paths.get(comparison.getNodeId());
        if (!usable(comparisonPath)) {
            return Recommendation.unavailable("no fresh measured comparison path");
        }

        BigDecimal targetRatio = configuration.getTargetMin()
                .add(configuration.getTargetMax().subtract(configuration.getTargetMin())
                        .multiply(TARGET_RANGE_POSITION))
                .setScale(3, RoundingMode.HALF_UP);

        // Controlled-bandwidth policy, not a prediction of unrestricted TCP throughput.
        // Each path contributes its own bottleneck bandwidth and accumulated latency.
        // 10 ms is the fixed latency scale of this policy; it is never fitted to task
        // outcomes. The reciprocal sum increases when EITHER participating path improves,
        // stays below both path budgets, and excludes unrelated slow compute nodes.
        BigDecimal centralBudget = pathBudget(centralPath);
        BigDecimal comparisonBudget = pathBudget(comparisonPath);
        BigDecimal centralRate = centralBudget.multiply(comparisonBudget)
                .divide(comparisonBudget.add(centralBudget.multiply(targetRatio)), 6, RoundingMode.DOWN)
                .multiply(configuration.getAdaptiveSafetyFactor());
        BigDecimal bytesPerSecond = centralized ? centralRate : centralRate.multiply(targetRatio);
        long kibPerSecond = Math.max(1L, bytesPerSecond.divide(BYTES_PER_KIB, 0, RoundingMode.DOWN).longValue());
        return Recommendation.available(kibPerSecond + "k", targetPath.getBandwidthMbps(),
                targetRatio, centralized, targetPath.getLatencyMs(), comparison.getNodeName());
    }

    private static boolean usable(NetworkTopologyService.NetworkPath path) {
        return path != null && path.getBandwidthMbps() > 0
                && path.getBandwidthMbps() != Long.MAX_VALUE
                && Double.isFinite(path.getLatencyMs()) && path.getLatencyMs() >= 0;
    }

    private static BigDecimal pathBudget(NetworkTopologyService.NetworkPath path) {
        BigDecimal latencyWeight = BigDecimal.ONE.add(BigDecimal.valueOf(path.getLatencyMs())
                .divide(BigDecimal.TEN, 9, RoundingMode.HALF_UP));
        return BigDecimal.valueOf(path.getBandwidthMbps()).multiply(BITS_PER_MEGABIT)
                .divide(BITS_PER_BYTE.multiply(latencyWeight), 6, RoundingMode.DOWN);
    }

    public static final class Recommendation {
        private final String rate;
        private final Long basisBandwidthMbps;
        private final BigDecimal targetRatio;
        private final boolean centralized;
        private final String unavailableReason;
        private final Double latencyMs;
        private final String comparisonNode;

        private Recommendation(String rate, Long basisBandwidthMbps, BigDecimal targetRatio,
                               boolean centralized, String unavailableReason, Double latencyMs, String comparisonNode) {
            this.rate = rate;
            this.basisBandwidthMbps = basisBandwidthMbps;
            this.targetRatio = targetRatio;
            this.centralized = centralized;
            this.unavailableReason = unavailableReason;
            this.latencyMs = latencyMs;
            this.comparisonNode = comparisonNode;
        }

        static Recommendation available(String rate, long bandwidthMbps,
                                        BigDecimal targetRatio, boolean centralized, double latencyMs, String comparisonNode) {
            return new Recommendation(rate, bandwidthMbps, targetRatio, centralized, null, latencyMs, comparisonNode);
        }

        static Recommendation unavailable(String reason) {
            return new Recommendation(null, null, null, false, reason, null, null);
        }

        public Double getLatencyMs() { return latencyMs; }
        public String getComparisonNode() { return comparisonNode; }
        public boolean isAvailable() { return rate != null; }
        public String getRate() { return rate; }
        public Long getBasisBandwidthMbps() { return basisBandwidthMbps; }
        public BigDecimal getTargetRatio() { return targetRatio; }
        public boolean isCentralized() { return centralized; }
        public String getUnavailableReason() { return unavailableReason; }
    }
}
