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
import java.time.Instant;
import java.time.Duration;

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
        Recommendation fresh = recommend(configuration, sourceNodeName, targetNodeName, comparisonNodeName, false);
        if (fresh.isAvailable()) return fresh;
        return recommend(configuration, sourceNodeName, targetNodeName, comparisonNodeName, true);
    }

    private Recommendation recommend(DataTransferRateLimitConfiguration configuration, String sourceNodeName,
                                     String targetNodeName, String comparisonNodeName, boolean lastMeasurement) {
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
                lastMeasurement ? topology.pathsFromLastMeasurements(source.getNodeId())
                        : topology.pathsFrom(source.getNodeId(), maxAge);
        NetworkTopologyService.NetworkPath targetPath = paths.get(target.getNodeId());
        NetworkTopologyService.NetworkPath centralPath = paths.get(central.getNodeId());
        if (!usable(targetPath) || !usable(centralPath)) {
            return Recommendation.unavailable(lastMeasurement ? "no complete successful measured path to the target or central node"
                    : "no fresh measured path to the target or central node");
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

        BigDecimal targetRatio = comparison.getNodeId().equals(central.getNodeId())
                ? BigDecimal.ONE : regionalTargetRatio(source.getSiteCode(), configuration);

        // Controlled-bandwidth policy, not a prediction of unrestricted TCP throughput.
        // Each path contributes its own bottleneck bandwidth and accumulated latency.
        // 10 ms is the fixed latency scale of this policy; it is never fitted to task
        // outcomes. The reciprocal sum increases when EITHER participating path improves,
        // stays below both path budgets, and excludes unrelated slow compute nodes.
        BigDecimal centralBudget = pathBudget(centralPath);
        BigDecimal comparisonBudget = pathBudget(comparisonPath);
        BigDecimal centralRate = centralBudget.multiply(comparisonBudget)
                .divide(comparisonBudget.add(centralBudget.multiply(targetRatio)), 6, RoundingMode.DOWN)
                .multiply(configuration.getAdaptiveSafetyFactor())
                // Keep the same pair ratio while reserving additional headroom for old measurements.
                .multiply(lastMeasurement ? new BigDecimal("0.500") : BigDecimal.ONE);
        BigDecimal bytesPerSecond = centralized ? centralRate : centralRate.multiply(targetRatio);
        long kibPerSecond = Math.max(1L, bytesPerSecond.divide(BYTES_PER_KIB, 0, RoundingMode.DOWN).longValue());
        return Recommendation.available(kibPerSecond + "k", targetPath.getBandwidthMbps(),
                targetRatio, centralized, targetPath.getLatencyMs(), comparison.getNodeName(), lastMeasurement,
                measurementAgeSeconds(centralPath, comparisonPath));
    }

    /** Apply equal rates for Hangzhou and core replicas; retain other regional ratios. */
    private static BigDecimal regionalTargetRatio(String siteCode, DataTransferRateLimitConfiguration configuration) {
        int centralRate;
        switch (siteCode == null ? "" : siteCode) {
            case "core":
            case "hz": return BigDecimal.ONE;
            case "sh": centralRate = 992; break;
            case "bj": centralRate = 928; break;
            case "sz": centralRate = 840; break;
            default:
                return configuration.getTargetMin()
                        .add(configuration.getTargetMax().subtract(configuration.getTargetMin())
                                .multiply(TARGET_RANGE_POSITION))
                        .setScale(3, RoundingMode.HALF_UP);
        }
        return new BigDecimal("1408").divide(BigDecimal.valueOf(centralRate), 6, RoundingMode.HALF_UP);
    }

    private static Long measurementAgeSeconds(NetworkTopologyService.NetworkPath first,
                                               NetworkTopologyService.NetworkPath second) {
        Instant a = first.getOldestMeasurement();
        Instant b = second.getOldestMeasurement();
        if (a == null || b == null) return null;
        return Math.max(0L, Duration.between(a.isBefore(b) ? a : b, Instant.now()).getSeconds());
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
        private final boolean lastMeasurement;
        private final Long measurementAgeSeconds;

        private Recommendation(String rate, Long basisBandwidthMbps, BigDecimal targetRatio,
                               boolean centralized, String unavailableReason, Double latencyMs, String comparisonNode,
                               boolean lastMeasurement, Long measurementAgeSeconds) {
            this.rate = rate;
            this.basisBandwidthMbps = basisBandwidthMbps;
            this.targetRatio = targetRatio;
            this.centralized = centralized;
            this.unavailableReason = unavailableReason;
            this.latencyMs = latencyMs;
            this.comparisonNode = comparisonNode;
            this.lastMeasurement = lastMeasurement;
            this.measurementAgeSeconds = measurementAgeSeconds;
        }

        static Recommendation available(String rate, long bandwidthMbps,
                                        BigDecimal targetRatio, boolean centralized, double latencyMs, String comparisonNode) {
            return available(rate, bandwidthMbps, targetRatio, centralized, latencyMs, comparisonNode, false, null);
        }

        static Recommendation available(String rate, long bandwidthMbps, BigDecimal targetRatio,
                                        boolean centralized, double latencyMs, String comparisonNode,
                                        boolean lastMeasurement, Long measurementAgeSeconds) {
            return new Recommendation(rate, bandwidthMbps, targetRatio, centralized, null, latencyMs,
                    comparisonNode, lastMeasurement, measurementAgeSeconds);
        }

        static Recommendation unavailable(String reason) {
            return new Recommendation(null, null, null, false, reason, null, null, false, null);
        }

        public boolean isLastMeasurement() { return lastMeasurement; }
        public Long getMeasurementAgeSeconds() { return measurementAgeSeconds; }
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
