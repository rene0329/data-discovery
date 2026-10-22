package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.auth.AuthException;
import org.example.auth.CurrentUserService;
import org.example.dto.admin.DataTransferRateLimitDtos;
import org.example.entity.DataTransferRateLimitConfiguration;
import org.example.factory.PathRateLimits;
import org.example.mapper.DataTransferRateLimitMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DataTransferRateLimitService {
    private static final BigDecimal MAX_TARGET = new BigDecimal("100");

    private final DataTransferRateLimitMapper mapper;
    private final CurrentUserService currentUsers;
    private final String fallbackDefaultRate;
    private final String fallbackPathRules;
    private final AdaptiveTransferRateService adaptiveRates;

    @Autowired
    public DataTransferRateLimitService(
            DataTransferRateLimitMapper mapper,
            CurrentUserService currentUsers,
            @Value("${dispatch.job.curl.limit-rate.default:}") String fallbackDefaultRate,
            @Value("${dispatch.job.curl.limit-rate.paths:}") String fallbackPathRules,
            AdaptiveTransferRateService adaptiveRates) {
        this.mapper = mapper;
        this.currentUsers = currentUsers;
        this.fallbackDefaultRate = normalizeRate(fallbackDefaultRate);
        this.fallbackPathRules = fallbackPathRules == null ? "" : fallbackPathRules.trim();
        this.adaptiveRates = adaptiveRates;
        PathRateLimits.parse(this.fallbackPathRules);
    }

    /** Kept for isolated unit tests and compatibility with existing embedders. */
    DataTransferRateLimitService(DataTransferRateLimitMapper mapper, CurrentUserService currentUsers,
                                 String fallbackDefaultRate, String fallbackPathRules) {
        this(mapper, currentUsers, fallbackDefaultRate, fallbackPathRules, null);
    }

    public DataTransferRateLimitDtos.View current() {
        return new DataTransferRateLimitDtos.View(requireCurrent());
    }

    public List<DataTransferRateLimitDtos.View> history(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return mapper.listHistory(limit).stream()
                .map(DataTransferRateLimitDtos.View::new).collect(Collectors.toList());
    }

    public DataTransferRateLimitDtos.Preview preview(String sourceNode, String targetNode) {
        Resolution resolution = resolve(sourceNode, targetNode);
        return new DataTransferRateLimitDtos.Preview(sourceNode, targetNode, resolution.getRate(),
                resolution.getSource(), resolution.getVersion(), resolution.getBasisBandwidthMbps(),
                resolution.getTargetRatio());
    }

    /**
     * Resolve the current database configuration for every new transfer. If the
     * configuration table is temporarily unavailable, retain the deployment
     * environment as a safe fallback rather than creating an unlimited job.
     */
    public Resolution resolve(String sourceNode, String targetNode) {
        return resolve(sourceNode, targetNode, null);
    }

    public Resolution resolve(String sourceNode, String targetNode, String comparisonNode) {
        try {
            DataTransferRateLimitConfiguration current = mapper.findCurrent();
            if (current != null) return resolve(current, sourceNode, targetNode, "database", comparisonNode);
        } catch (RuntimeException ex) {
            log.warn("读取动态传输配置失败，使用部署配置回退: {}", ex.getMessage());
        }
        DataTransferRateLimitConfiguration fallback = new DataTransferRateLimitConfiguration();
        fallback.setVersion(0L);
        fallback.setEnabled(true);
        fallback.setAdaptiveEnabled(false);
        fallback.setDefaultRate(fallbackDefaultRate);
        fallback.setPathRules(fallbackPathRules);
        return resolve(fallback, sourceNode, targetNode, "deployment-fallback", comparisonNode);
    }

    @Transactional
    public DataTransferRateLimitDtos.View update(DataTransferRateLimitDtos.UpdateRequest request) {
        if (request == null) invalid("request body is required");
        DataTransferRateLimitConfiguration current = requireCurrent();
        requireVersion(request.getVersion(), current.getVersion());
        boolean enabled = request.getEnabled() == null ? Boolean.TRUE.equals(current.getEnabled()) : request.getEnabled();
        boolean adaptiveEnabled = request.getAdaptiveEnabled() == null
                ? Boolean.TRUE.equals(current.getAdaptiveEnabled()) : request.getAdaptiveEnabled();
        boolean bandwidthProbeEnabled = request.getBandwidthProbeEnabled() == null
                ? Boolean.TRUE.equals(current.getBandwidthProbeEnabled()) : request.getBandwidthProbeEnabled();
        String defaultRate = request.getDefaultRate() == null
                ? current.getDefaultRate() : normalizeRate(request.getDefaultRate());
        String pathRules = request.getPathRules() == null ? current.getPathRules() : request.getPathRules().trim();
        BigDecimal targetMin = request.getTargetMin() == null ? current.getTargetMin() : request.getTargetMin();
        BigDecimal targetMax = request.getTargetMax() == null ? current.getTargetMax() : request.getTargetMax();
        BigDecimal safetyFactor = request.getAdaptiveSafetyFactor() == null
                ? current.getAdaptiveSafetyFactor() : request.getAdaptiveSafetyFactor();
        Integer maxAgeSeconds = request.getMeasurementMaxAgeSeconds() == null
                ? current.getMeasurementMaxAgeSeconds() : request.getMeasurementMaxAgeSeconds();
        String centralNode = request.getAdaptiveCentralNode() == null
                ? current.getAdaptiveCentralNode() : request.getAdaptiveCentralNode().trim();
        validate(enabled, adaptiveEnabled, defaultRate, pathRules, targetMin, targetMax,
                safetyFactor, maxAgeSeconds, centralNode);
        return persist(current, enabled, adaptiveEnabled, bandwidthProbeEnabled,
                defaultRate, pathRules, targetMin, targetMax,
                safetyFactor, maxAgeSeconds, centralNode);
    }

    @Transactional
    public DataTransferRateLimitDtos.View rollback(DataTransferRateLimitDtos.RollbackRequest request) {
        if (request == null || request.getRestoreVersion() == null) invalid("restoreVersion is required");
        DataTransferRateLimitConfiguration current = requireCurrent();
        requireVersion(request.getVersion(), current.getVersion());
        DataTransferRateLimitConfiguration historical = mapper.findHistoryVersion(request.getRestoreVersion());
        if (historical == null) {
            throw new AuthException(HttpStatus.NOT_FOUND, "RATE_LIMIT_VERSION_NOT_FOUND",
                    "the requested rate-limit version was not found");
        }
        validate(Boolean.TRUE.equals(historical.getEnabled()), Boolean.TRUE.equals(historical.getAdaptiveEnabled()),
                historical.getDefaultRate(), historical.getPathRules(), historical.getTargetMin(),
                historical.getTargetMax(), historical.getAdaptiveSafetyFactor(),
                historical.getMeasurementMaxAgeSeconds(), historical.getAdaptiveCentralNode());
        return persist(current, Boolean.TRUE.equals(historical.getEnabled()),
                Boolean.TRUE.equals(historical.getAdaptiveEnabled()),
                Boolean.TRUE.equals(historical.getBandwidthProbeEnabled()), historical.getDefaultRate(),
                historical.getPathRules(), historical.getTargetMin(), historical.getTargetMax(),
                historical.getAdaptiveSafetyFactor(), historical.getMeasurementMaxAgeSeconds(),
                historical.getAdaptiveCentralNode());
    }

    private DataTransferRateLimitDtos.View persist(DataTransferRateLimitConfiguration current,
                                                    boolean enabled, boolean adaptiveEnabled,
                                                    boolean bandwidthProbeEnabled,
                                                    String defaultRate, String pathRules,
                                                    BigDecimal targetMin, BigDecimal targetMax,
                                                    BigDecimal safetyFactor, Integer maxAgeSeconds,
                                                    String centralNode) {
        long newVersion = current.getVersion() + 1L;
        String username = currentUsers.currentUser().getUsername();
        int changed = mapper.update(current.getVersion(), newVersion, enabled, adaptiveEnabled,
                bandwidthProbeEnabled, defaultRate,
                pathRules, targetMin, targetMax, safetyFactor, maxAgeSeconds, centralNode, username);
        if (changed != 1) {
            throw new AuthException(HttpStatus.CONFLICT, "RATE_LIMIT_VERSION_CONFLICT",
                    "the configuration changed; reload it before saving");
        }
        mapper.insertCurrentHistory();
        DataTransferRateLimitConfiguration updated = requireCurrent();
        log.debug("管理员 {} 更新传输配置到版本 {}: enabled={}, adaptive={}, bandwidthProbe={}, paths='{}'",
                username, updated.getVersion(), updated.getEnabled(), updated.getAdaptiveEnabled(),
                updated.getBandwidthProbeEnabled(), updated.getPathRules());
        return new DataTransferRateLimitDtos.View(updated);
    }

    private Resolution resolve(DataTransferRateLimitConfiguration configuration,
                               String sourceNode, String targetNode, String source, String comparisonNode) {
        if (!Boolean.TRUE.equals(configuration.getEnabled())) {
            return new Resolution(null, configuration.getVersion(), source);
        }
        if (Boolean.TRUE.equals(configuration.getAdaptiveEnabled()) && adaptiveRates != null) {
            AdaptiveTransferRateService.Recommendation recommendation =
                    adaptiveRates.recommend(configuration, sourceNode, targetNode, comparisonNode);
            if (recommendation.isAvailable()) {
                log.debug("传输配置 v{}: {} → {}, rate={}, bandwidth={}Mbps, latency={}ms, comparisonNode={}, targetRatio={}, measurementSource={}, measurementAgeSeconds={}",
                        configuration.getVersion(), sourceNode, targetNode, recommendation.getRate(),
                        recommendation.getBasisBandwidthMbps(), recommendation.getLatencyMs(),
                        recommendation.getComparisonNode(), recommendation.getTargetRatio(),
                        recommendation.isLastMeasurement() ? "LAST_SUCCESSFUL_MEASUREMENT" : "FRESH",
                        recommendation.getMeasurementAgeSeconds());
                return new Resolution(recommendation.getRate(), configuration.getVersion(),
                        source + (recommendation.isLastMeasurement() ? "-last-measurement-fallback" : "-adaptive"),
                        recommendation.getBasisBandwidthMbps(),
                        recommendation.getTargetRatio());
            }
            log.warn("动态传输配置无法用于 {} → {}，使用人工规则回退: {}", sourceNode, targetNode,
                    recommendation.getUnavailableReason());
            source = source + "-static-fallback";
        }
        String pathRate = sourceNode == null || targetNode == null ? null
                : PathRateLimits.parse(configuration.getPathRules()).resolve(sourceNode, targetNode);
        String rate = pathRate != null ? pathRate : normalizeRate(configuration.getDefaultRate());
        return new Resolution(rate, configuration.getVersion(), source);
    }

    private DataTransferRateLimitConfiguration requireCurrent() {
        DataTransferRateLimitConfiguration current = mapper.findCurrent();
        if (current == null) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_CONFIG_MISSING",
                    "the transfer rate-limit configuration is not initialized");
        }
        return current;
    }

    private void validate(boolean enabled, boolean adaptiveEnabled, String defaultRate, String pathRules,
                          BigDecimal targetMin, BigDecimal targetMax, BigDecimal safetyFactor,
                          Integer maxAgeSeconds, String centralNode) {
        try {
            PathRateLimits parsed = PathRateLimits.parse(pathRules);
            if (enabled && !adaptiveEnabled && parsed.isEmpty() && defaultRate == null) {
                invalid("enabled configuration must contain a default rate or at least one path rule");
            }
        } catch (IllegalArgumentException ex) {
            invalid(ex.getMessage());
        }
        if (defaultRate != null && !PathRateLimits.isValidRate(defaultRate)) invalid("default rate is invalid");
        if (targetMin == null || targetMax == null || targetMin.signum() <= 0
                || targetMax.compareTo(targetMin) < 0 || targetMax.compareTo(MAX_TARGET) > 0) {
            invalid("target range must be positive, ordered and no greater than 100");
        }
        if (safetyFactor == null || safetyFactor.compareTo(new BigDecimal("0.100")) < 0
                || safetyFactor.compareTo(new BigDecimal("0.990")) > 0) {
            invalid("adaptive safety factor must be between 0.100 and 0.990");
        }
        if (maxAgeSeconds == null || maxAgeSeconds < 60 || maxAgeSeconds > 86400) {
            invalid("measurement maximum age must be between 60 and 86400 seconds");
        }
        if (centralNode == null || centralNode.isEmpty() || centralNode.contains(",")
                || centralNode.contains("->") || centralNode.contains("=")) {
            invalid("adaptive central node is invalid");
        }
    }

    private void requireVersion(Long supplied, Long current) {
        if (supplied == null) invalid("version is required");
        if (!supplied.equals(current)) {
            throw new AuthException(HttpStatus.CONFLICT, "RATE_LIMIT_VERSION_CONFLICT",
                    "the configuration changed; reload it before saving");
        }
    }

    private static String normalizeRate(String rate) {
        if (rate == null || rate.trim().isEmpty() || "0".equals(rate.trim())) return null;
        return rate.trim();
    }

    private void invalid(String message) {
        throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_RATE_LIMIT_CONFIG", message);
    }

    public static final class Resolution {
        private final String rate;
        private final Long version;
        private final String source;
        private final Long basisBandwidthMbps;
        private final BigDecimal targetRatio;

        Resolution(String rate, Long version, String source) {
            this(rate, version, source, null, null);
        }

        Resolution(String rate, Long version, String source, Long basisBandwidthMbps,
                   BigDecimal targetRatio) {
            this.rate = rate;
            this.version = version;
            this.source = source;
            this.basisBandwidthMbps = basisBandwidthMbps;
            this.targetRatio = targetRatio;
        }

        public String getRate() { return rate; }
        public Long getVersion() { return version; }
        public String getSource() { return source; }
        public Long getBasisBandwidthMbps() { return basisBandwidthMbps; }
        public BigDecimal getTargetRatio() { return targetRatio; }
    }
}
