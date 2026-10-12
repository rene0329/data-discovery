package org.example.service;

import org.example.entity.DataTransferRateLimitConfiguration;
import org.example.entity.NodeManagement;
import org.example.mapper.NodeManagementMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Calculates a conservative per-transfer curl rate from recent topology measurements. */
@Service
public class AdaptiveTransferRateService {
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
        if (targetPath == null || centralPath == null) {
            return Recommendation.unavailable("no fresh measured path to the target or central node");
        }

        // Use one conservative floor for every target reachable from the same source. This
        // prevents a fast same-site path from destroying the requested centralized/in-place
        // ratio when the centralized path crosses a slower regional uplink.
        long bandwidthFloorMbps = Math.min(targetPath.getBandwidthMbps(), centralPath.getBandwidthMbps());
        List<NodeManagement> computeNodes = nodes.getComputeCapableNodes();
        if (computeNodes != null) {
            for (NodeManagement compute : computeNodes) {
                if (compute == null || compute.getNodeId() == null
                        || compute.getNodeId().equals(source.getNodeId())) continue;
                NetworkTopologyService.NetworkPath path = paths.get(compute.getNodeId());
                if (path != null && path.getBandwidthMbps() > 0) {
                    bandwidthFloorMbps = Math.min(bandwidthFloorMbps, path.getBandwidthMbps());
                }
            }
        }
        if (bandwidthFloorMbps <= 0 || bandwidthFloorMbps == Long.MAX_VALUE) {
            return Recommendation.unavailable("fresh bandwidth floor is unavailable");
        }

        BigDecimal targetRatio = configuration.getTargetMin().add(configuration.getTargetMax())
                .divide(new BigDecimal("2"), 3, RoundingMode.HALF_UP);
        BigDecimal safetyFactor = configuration.getAdaptiveSafetyFactor();
        BigDecimal bytesPerSecond = BigDecimal.valueOf(bandwidthFloorMbps)
                .multiply(BITS_PER_MEGABIT)
                .divide(BITS_PER_BYTE, 6, RoundingMode.DOWN)
                .multiply(safetyFactor);
        boolean centralized = targetNodeName.equalsIgnoreCase(configuration.getAdaptiveCentralNode());
        if (centralized) {
            bytesPerSecond = bytesPerSecond.divide(targetRatio, 6, RoundingMode.DOWN);
        }
        long kibPerSecond = Math.max(1L, bytesPerSecond.divide(BYTES_PER_KIB, 0, RoundingMode.DOWN).longValue());
        return Recommendation.available(kibPerSecond + "k", bandwidthFloorMbps, targetRatio, centralized);
    }

    public static final class Recommendation {
        private final String rate;
        private final Long basisBandwidthMbps;
        private final BigDecimal targetRatio;
        private final boolean centralized;
        private final String unavailableReason;

        private Recommendation(String rate, Long basisBandwidthMbps, BigDecimal targetRatio,
                               boolean centralized, String unavailableReason) {
            this.rate = rate;
            this.basisBandwidthMbps = basisBandwidthMbps;
            this.targetRatio = targetRatio;
            this.centralized = centralized;
            this.unavailableReason = unavailableReason;
        }

        static Recommendation available(String rate, long bandwidthMbps,
                                        BigDecimal targetRatio, boolean centralized) {
            return new Recommendation(rate, bandwidthMbps, targetRatio, centralized, null);
        }

        static Recommendation unavailable(String reason) {
            return new Recommendation(null, null, null, false, reason);
        }

        public boolean isAvailable() { return rate != null; }
        public String getRate() { return rate; }
        public Long getBasisBandwidthMbps() { return basisBandwidthMbps; }
        public BigDecimal getTargetRatio() { return targetRatio; }
        public boolean isCentralized() { return centralized; }
        public String getUnavailableReason() { return unavailableReason; }
    }
}
