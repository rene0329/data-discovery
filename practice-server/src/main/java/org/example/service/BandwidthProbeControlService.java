package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.DataTransferRateLimitConfiguration;
import org.example.mapper.DataTransferRateLimitMapper;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class BandwidthProbeControlService {
    private final DataTransferRateLimitMapper configurations;
    private final ActiveDataTransferRegistry activeTransfers;

    public BandwidthProbeControlService(DataTransferRateLimitMapper configurations,
                                        ActiveDataTransferRegistry activeTransfers) {
        this.configurations = configurations;
        this.activeTransfers = activeTransfers;
    }

    public Decision decide(String sourceNode, String targetNode) {
        if (sourceNode == null || sourceNode.trim().isEmpty()
                || targetNode == null || targetNode.trim().isEmpty()) {
            return Decision.denied(false, "INVALID_LINK", activeTransfers.activeTransferCount());
        }
        final DataTransferRateLimitConfiguration configuration;
        try {
            configuration = configurations.findCurrent();
        } catch (RuntimeException ex) {
            log.warn("读取带宽探测开关失败，拒绝主动探测: {}", ex.getMessage());
            return Decision.denied(false, "CONFIG_UNAVAILABLE", activeTransfers.activeTransferCount());
        }
        boolean enabled = configuration != null
                && Boolean.TRUE.equals(configuration.getBandwidthProbeEnabled());
        if (!enabled) {
            return Decision.denied(false, "DISABLED", activeTransfers.activeTransferCount());
        }
        if (activeTransfers.isLinkBusy(sourceNode, targetNode)) {
            return Decision.denied(true, "BUSINESS_TRANSFER_ACTIVE", activeTransfers.activeTransferCount());
        }
        return Decision.allowed(activeTransfers.activeTransferCount());
    }

    public static final class Decision {
        private final boolean enabled;
        private final boolean permitted;
        private final String reason;
        private final int activeTransferCount;

        private Decision(boolean enabled, boolean permitted, String reason, int activeTransferCount) {
            this.enabled = enabled;
            this.permitted = permitted;
            this.reason = reason;
            this.activeTransferCount = activeTransferCount;
        }

        static Decision allowed(int activeTransferCount) {
            return new Decision(true, true, "IDLE", activeTransferCount);
        }

        static Decision denied(boolean enabled, String reason, int activeTransferCount) {
            return new Decision(enabled, false, reason, activeTransferCount);
        }

        public boolean isEnabled() { return enabled; }
        public boolean isPermitted() { return permitted; }
        public String getReason() { return reason; }
        public int getActiveTransferCount() { return activeTransferCount; }
    }
}
