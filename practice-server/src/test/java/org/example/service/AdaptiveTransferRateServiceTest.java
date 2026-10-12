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

        Map<Integer, NetworkTopologyService.NetworkPath> paths = new HashMap<>();
        paths.put(1, new NetworkTopologyService.NetworkPath(Collections.singletonList(1), 0, Long.MAX_VALUE));
        paths.put(2, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 4, 2), 30, 10));
        paths.put(3, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 3), 1, 3000));
        when(topology.pathsFrom(1, 900)).thenReturn(paths);
    }

    @Test
    void usesOneMeasuredFloorToPreserveTheRequestedRatioAcrossDifferentTargets() {
        AdaptiveTransferRateService.Recommendation centralized =
                service.recommend(configuration, "cluster-sz-3", "master-40");
        AdaptiveTransferRateService.Recommendation distributed =
                service.recommend(configuration, "cluster-sz-3", "cluster-sz-1");

        assertEquals("673k", centralized.getRate());
        assertEquals("976k", distributed.getRate());
        assertEquals(10L, centralized.getBasisBandwidthMbps());
        assertEquals(new BigDecimal("1.450"), centralized.getTargetRatio());
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
        assertEquals("no fresh measured path to the target or central node", result.getUnavailableReason());
    }

    private NodeManagement node(int id, String name) {
        return NodeManagement.builder().nodeId(id).nodeName(name).build();
    }
}
