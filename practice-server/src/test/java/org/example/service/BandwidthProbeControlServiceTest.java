package org.example.service;

import org.example.entity.DataTransferRateLimitConfiguration;
import org.example.entity.NodeManagement;
import org.example.mapper.DataTransferRateLimitMapper;
import org.example.mapper.NodeManagementMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BandwidthProbeControlServiceTest {
    @Test
    void probeSwitchIsOffByDefault() {
        DataTransferRateLimitMapper configurations = mock(DataTransferRateLimitMapper.class);
        ActiveDataTransferRegistry registry = mock(ActiveDataTransferRegistry.class);
        DataTransferRateLimitConfiguration configuration = new DataTransferRateLimitConfiguration();
        configuration.setBandwidthProbeEnabled(false);
        when(configurations.findCurrent()).thenReturn(configuration);

        BandwidthProbeControlService.Decision decision =
                new BandwidthProbeControlService(configurations, registry).decide("node-a", "node-b");

        assertFalse(decision.isPermitted());
        assertEquals("DISABLED", decision.getReason());
    }

    @Test
    void enabledProbeSkipsABusyRouteAndAllowsAnIdleLink() {
        DataTransferRateLimitMapper configurations = mock(DataTransferRateLimitMapper.class);
        DataTransferRateLimitConfiguration configuration = new DataTransferRateLimitConfiguration();
        configuration.setBandwidthProbeEnabled(true);
        when(configurations.findCurrent()).thenReturn(configuration);

        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        NetworkTopologyService topology = mock(NetworkTopologyService.class);
        NodeManagement source = node(1, "source");
        NodeManagement gateway = node(2, "gateway");
        NodeManagement target = node(3, "target");
        when(nodes.getNodeByName("source")).thenReturn(source);
        when(nodes.getNodeByName("target")).thenReturn(target);
        when(nodes.getNodeById(1)).thenReturn(source);
        when(nodes.getNodeById(2)).thenReturn(gateway);
        when(nodes.getNodeById(3)).thenReturn(target);
        Map<Integer, NetworkTopologyService.NetworkPath> paths = new HashMap<>();
        paths.put(3, new NetworkTopologyService.NetworkPath(Arrays.asList(1, 2, 3), 2.0, 20));
        when(topology.pathsFrom(1)).thenReturn(paths);
        ActiveDataTransferRegistry registry = new ActiveDataTransferRegistry(nodes, topology);
        registry.begin("job-1", "source", "target");
        BandwidthProbeControlService service = new BandwidthProbeControlService(configurations, registry);

        BandwidthProbeControlService.Decision busy = service.decide("source", "gateway");
        BandwidthProbeControlService.Decision idle = service.decide("other-a", "other-b");

        assertFalse(busy.isPermitted());
        assertEquals("BUSINESS_TRANSFER_ACTIVE", busy.getReason());
        assertTrue(idle.isPermitted());
        registry.complete("job-1");
        assertTrue(service.decide("source", "gateway").isPermitted());
    }

    private static NodeManagement node(int id, String name) {
        return NodeManagement.builder().nodeId(id).nodeName(name).build();
    }
}
