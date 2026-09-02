package org.example.controller.admin;

import org.example.entity.EdgeManagement;
import org.example.entity.NodeManagement;
import org.example.mapper.DataManagementMapper;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.MigrationTaskMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.service.K8sTaskOrchestratorService;
import org.example.service.NodeAvailabilityService;
import org.example.service.NetworkTopologyService;
import org.example.vo.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommonControllerTopologyTest {
    @Test
    @SuppressWarnings("unchecked")
    void topologyKeepsDisabledNodesForManagementAndFiltersRuntimeView() {
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        EdgeManagementMapper edges = mock(EdgeManagementMapper.class);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        NodeManagement active = NodeManagement.builder().nodeId(1).nodeName("active")
                .internalIp("10.0.0.1").externalIp("8.8.8.8")
                .registrationStatus("ACTIVE").enabled(true).observedStatus("ONLINE")
                .lastSeenAt(now).build();
        NodeManagement disabled = NodeManagement.builder().nodeId(2).nodeName("disabled")
                .registrationStatus("DISABLED").enabled(false).observedStatus("ONLINE")
                .lastSeenAt(now).build();
        when(nodes.networkConstruction()).thenReturn(Arrays.asList(active, disabled));
        when(edges.links()).thenReturn(Arrays.asList(
                EdgeManagement.builder().edgeId(1).sourceId(1).targetId(2).latency(5.0).bandwidth(10L).build()));
        CommonController controller = new CommonController(mock(DataManagementMapper.class), nodes,
                mock(TaskManagementMapper.class), mock(MigrationTaskMapper.class),
                new NetworkTopologyService(edges, nodes, new NodeAvailabilityService(300), 1800),
                mock(K8sTaskOrchestratorService.class), mock(RestTemplate.class),
                new NodeAvailabilityService(300));

        ResponseEntity<ApiResponse<Map<String, Object>>> management = controller.networkTopology(false);
        Map<String, Object> managementData = management.getBody().getData();
        assertEquals(2, ((List<?>) managementData.get("nodes")).size());
        Map<String, Object> firstNode = (Map<String, Object>) ((List<?>) managementData.get("nodes")).get(0);
        assertEquals(1, firstNode.get("nodeId"));
        assertEquals("10.0.0.1", firstNode.get("internalIp"));
        assertEquals("8.8.8.8", firstNode.get("externalIp"));
        org.junit.jupiter.api.Assertions.assertNull(firstNode.get("cpu"));
        org.junit.jupiter.api.Assertions.assertNull(firstNode.get("memory"));
        Map<String, Object> edge = (Map<String, Object>) ((List<?>) managementData.get("edges")).get(0);
        assertFalse((Boolean) edge.get("active"));

        ResponseEntity<ApiResponse<Map<String, Object>>> runtime = controller.networkTopology(true);
        Map<String, Object> runtimeData = runtime.getBody().getData();
        assertEquals(1, ((List<?>) runtimeData.get("nodes")).size());
        assertEquals(0, ((List<?>) runtimeData.get("edges")).size());
    }
}
