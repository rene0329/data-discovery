package org.example.service;

import org.example.entity.DataTransferRateLimitConfiguration;
import org.example.entity.NodeManagement;
import org.example.mapper.NodeManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdaptiveTransferRateServiceTest {
    private NodeManagementMapper nodes;
    private NetworkTopologyService topology;
    private AdaptiveTransferRateService service;
    private DataTransferRateLimitConfiguration configuration;
    private Map<Integer, NetworkTopologyService.NetworkPath> paths;

    @BeforeEach
    void setUp() {
        nodes = mock(NodeManagementMapper.class);
        topology = mock(NetworkTopologyService.class);
        service = new AdaptiveTransferRateService(nodes, topology);
        configuration = new DataTransferRateLimitConfiguration();
        configuration.setAdaptiveEnabled(true);
        configuration.setAdaptiveSafetyFactor(new BigDecimal("0.800"));
        configuration.setMeasurementMaxAgeSeconds(900);
        configuration.setAdaptiveCentralNode("master-40");
        configuration.setTargetMin(new BigDecimal("1.400"));
        configuration.setTargetMax(new BigDecimal("1.500"));

        NodeManagement source = node(1, "cluster-sz-3");
        NodeManagement central = node(2, "master-40");
        NodeManagement localCompute = node(3, "cluster-sz-1");
        when(nodes.getNodeByName("cluster-sz-3")).thenReturn(source);
        when(nodes.getNodeByName("master-40")).thenReturn(central);
        when(nodes.getNodeByName("cluster-sz-1")).thenReturn(localCompute);
        when(nodes.getComputeCapableNodes()).thenReturn(Arrays.asList(central, localCompute));

        paths = new HashMap<>();
        paths.put(1, new NetworkTopologyService.NetworkPath(Collections.singletonList(1), 0, Long.MAX_VALUE));
        paths.put(2, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 4, 2), 30, 10));
        paths.put(3, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 3), 1, 3000));
        when(topology.pathsFrom(1, 900)).thenReturn(paths);
    }

    @Test
    void usesOnlyThePairedPathsAndPreservesTheConfiguredRateRatio() {
        AdaptiveTransferRateService.Recommendation centralized =
                service.recommend(configuration, "cluster-sz-3", "master-40");
        AdaptiveTransferRateService.Recommendation distributed =
                service.recommend(configuration, "cluster-sz-3", "cluster-sz-1");

        assertEquals("243k", centralized.getRate());
        assertEquals("360k", distributed.getRate());
        assertEquals(10L, centralized.getBasisBandwidthMbps());
        assertEquals(3000L, distributed.getBasisBandwidthMbps());
        assertEquals("cluster-sz-1", centralized.getComparisonNode());
        assertEquals(new BigDecimal("1.480"), centralized.getTargetRatio());
        assertTrue(centralized.isCentralized());
        assertFalse(distributed.isCentralized());
    }

    @Test
    void refusesToAdaptWhenRecentMeasurementsCannotReachTheCentralNode() {
        when(topology.pathsFrom(1, 900)).thenReturn(Collections.singletonMap(1,
                new NetworkTopologyService.NetworkPath(Collections.singletonList(1), 0, Long.MAX_VALUE)));

        AdaptiveTransferRateService.Recommendation result =
                service.recommend(configuration, "cluster-sz-3", "cluster-sz-1");

        assertFalse(result.isAvailable());
        assertEquals("no complete successful measured path to the target or central node", result.getUnavailableReason());
    }

    @Test
    void shorteningTheCentralPathChangesBothRatesAtTheSameBandwidth() {
        long before = rate("master-40");
        paths.put(2, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 2), 3, 10));
        assertTrue(rate("master-40") > before * 2);
        assertRatio();
    }

    @Test
    void improvingOnlyTheDistributedPathAlsoChangesTheRate() {
        paths.put(3, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 3), 20, 10));
        long before = rate("cluster-sz-1");
        paths.put(3, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 3), 1, 10));
        assertTrue(rate("cluster-sz-1") > before);
        assertRatio();
    }

    @Test
    void improvingBandwidthChangesTheRateAtTheSameLatency() {
        long before = rate("master-40");
        paths.put(2, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 2), 30, 20));
        assertTrue(rate("master-40") > before);
        assertRatio();
    }

    @Test
    void unrelatedSlowComputeNodeDoesNotThrottleThePair() {
        long before = rate("master-40");
        paths.put(9, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 9), 100, 1));
        when(nodes.getComputeCapableNodes()).thenReturn(Arrays.asList(
                node(9, "unrelated"), node(2, "master-40"), node(3, "cluster-sz-1")));
        assertEquals(before, rate("master-40"));
    }

    @Test
    void differentDistributedTargetsUseTheirOwnPathMeasurements() {
        when(nodes.getNodeByName("other-compute")).thenReturn(node(9, "other-compute"));
        paths.put(9, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 9), 100, 1));
        long other = Long.parseLong(service.recommend(configuration, "cluster-sz-3", "other-compute")
                .getRate().replace("k", ""));
        assertTrue(other < rate("cluster-sz-1"));
    }

    @Test
    void prefersSameSiteComparisonIndependentlyOfDatabaseOrder() {
        NodeManagement source = node(1, "cluster-sz-3");
        source.setSiteCode("sz");
        when(nodes.getNodeByName("cluster-sz-3")).thenReturn(source);
        NodeManagement sameSite = node(3, "cluster-sz-1");
        sameSite.setSiteCode("sz");
        paths.put(9, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 9), 0.1, 9000));
        when(nodes.getComputeCapableNodes()).thenReturn(Arrays.asList(node(9, "other"), sameSite));
        assertEquals("cluster-sz-1", service.recommend(configuration, "cluster-sz-3", "master-40")
                .getComparisonNode());
    }

    @Test
    void fallsBackForMissingBandwidth() {
        paths.put(2, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 2), 30, 0));
        assertFalse(service.recommend(configuration, "cluster-sz-3", "master-40").isAvailable());
    }

    @Test
    void fallsBackWhenNoMeasuredComparisonNodeExists() {
        when(nodes.getComputeCapableNodes()).thenReturn(Collections.singletonList(node(2, "master-40")));
        assertFalse(service.recommend(configuration, "cluster-sz-3", "master-40").isAvailable());
    }

    @Test
    void retainsTheProductionTargetRange() {
        configuration.setTargetMin(new BigDecimal("1.300"));
        configuration.setTargetMax(new BigDecimal("1.690"));
        assertEquals(new BigDecimal("1.612"), service.recommend(configuration,
                "cluster-sz-3", "master-40").getTargetRatio());
        assertRatio();
    }

    @Test
    void centralRateUsesTheActualFallbackTargetSelectedForThisDataset() {
        when(nodes.getNodeByName("resource-fallback")).thenReturn(node(9, "resource-fallback"));
        paths.put(9, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 9), 35, 10));
        long central = Long.parseLong(service.recommend(configuration, "cluster-sz-3", "master-40",
                "resource-fallback").getRate().replace("k", ""));
        long distributed = Long.parseLong(service.recommend(configuration, "cluster-sz-3", "resource-fallback")
                .getRate().replace("k", ""));
        double ratio = (double) distributed / central;
        assertTrue(ratio >= 1.4 && ratio <= 1.5);
        assertTrue(central < rate("master-40"));
    }

    @Test
    void doesNotSilentlyReplaceAnUnavailableExplicitComparisonTarget() {
        assertFalse(service.recommend(configuration, "cluster-sz-3", "master-40", "missing-node").isAvailable());
    }

    @Test
    void expiredMeasurementsRetainPathDifferencesAndThePairedRatio() {
        when(topology.pathsFrom(1, 900)).thenReturn(Collections.emptyMap());
        when(topology.pathsFromLastMeasurements(1)).thenReturn(paths);
        AdaptiveTransferRateService.Recommendation central = service.recommend(configuration, "cluster-sz-3", "master-40");
        AdaptiveTransferRateService.Recommendation distributed = service.recommend(configuration, "cluster-sz-3", "cluster-sz-1");
        assertTrue(central.isLastMeasurement());
        assertTrue(distributed.isLastMeasurement());
        assertEquals("121k", central.getRate());
        assertEquals("180k", distributed.getRate());
        assertRatio();
        long before = rate("master-40");
        paths.put(2, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 2), 3, 10));
        assertTrue(rate("master-40") > before);
        assertRatio();
    }

    @Test
    void expiredFallbackUsesTheActualResourceFallbackTarget() {
        when(topology.pathsFrom(1, 900)).thenReturn(Collections.emptyMap());
        when(topology.pathsFromLastMeasurements(1)).thenReturn(paths);
        when(nodes.getNodeByName("fallback-compute")).thenReturn(node(9, "fallback-compute"));
        paths.put(9, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 9), 35, 10));
        long central = Long.parseLong(service.recommend(configuration, "cluster-sz-3", "master-40", "fallback-compute")
                .getRate().replace("k", ""));
        long distributed = Long.parseLong(service.recommend(configuration, "cluster-sz-3", "fallback-compute")
                .getRate().replace("k", ""));
        assertTrue((double) distributed / central >= 1.4 && (double) distributed / central <= 1.5);
    }

    @Test
    void recentMeasurementsTakePriorityOverTheFallback() {
        assertFalse(service.recommend(configuration, "cluster-sz-3", "master-40").isLastMeasurement());
        org.mockito.Mockito.verify(topology, org.mockito.Mockito.never()).pathsFromLastMeasurements(1);
    }

    private long rate(String target) {
        return Long.parseLong(service.recommend(configuration, "cluster-sz-3", target).getRate().replace("k", ""));
    }

    private void assertRatio() {
        double ratio = (double) rate("cluster-sz-1") / rate("master-40");
        assertTrue(ratio >= configuration.getTargetMin().doubleValue());
        assertTrue(ratio <= configuration.getTargetMax().doubleValue());
    }

    private NodeManagement node(int id, String name) {
        return NodeManagement.builder().nodeId(id).nodeName(name).build();
    }
}
