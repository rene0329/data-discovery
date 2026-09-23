package org.example.controller.admin;

import org.example.entity.NodeManagement;
import org.example.mapper.DataManagementMapper;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.MigrationTaskMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.service.K8sTaskOrchestratorService;
import org.example.service.NetworkTopologyService;
import org.example.service.NodeAvailabilityService;
import org.example.service.PublicIpLocationService;
import org.example.vo.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommonControllerNodeMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void nodeMetricsFillsDiskUsageFromAgentHealth() {
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        NodeManagement node = NodeManagement.builder().nodeId(1).nodeName("n1").internalIp("10.0.0.1").build();
        when(nodes.getNodeById(1)).thenReturn(node);
        Map<String, Object> body = new HashMap<>();
        body.put("diskTotalBytes", 10L * 1024 * 1024 * 1024);
        body.put("diskUsedBytes", 4L * 1024 * 1024 * 1024);
        when(restTemplate.getForEntity(eq("http://10.0.0.1:8080/data-discovery/health"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(body));
        CommonController controller = controller(nodes, restTemplate);

        ResponseEntity<ApiResponse<NodeManagement>> result = controller.nodeMetrics(1);

        NodeManagement data = result.getBody().getData();
        assertEquals(10.0, data.getMaxDisk());
        assertEquals(4.0, data.getCurrentDisk());
    }

    @Test
    void nodeMetricsLeavesDiskUsageNullWhenAgentUnreachable() {
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        NodeManagement node = NodeManagement.builder().nodeId(2).nodeName("n2").internalIp("10.0.0.2").build();
        when(nodes.getNodeById(2)).thenReturn(node);
        when(restTemplate.getForEntity(anyString(), eq(Map.class))).thenThrow(new RestClientException("refused"));
        CommonController controller = controller(nodes, restTemplate);

        ResponseEntity<ApiResponse<NodeManagement>> result = controller.nodeMetrics(2);

        assertNull(result.getBody().getData().getMaxDisk());
        assertNull(result.getBody().getData().getCurrentDisk());
    }

    private CommonController controller(NodeManagementMapper nodes, RestTemplate restTemplate) {
        EdgeManagementMapper edges = mock(EdgeManagementMapper.class);
        CommonController controller = new CommonController(mock(DataManagementMapper.class), nodes,
                mock(TaskManagementMapper.class), mock(MigrationTaskMapper.class),
                new NetworkTopologyService(edges, nodes, new NodeAvailabilityService(300), 1800),
                mock(K8sTaskOrchestratorService.class), restTemplate,
                new NodeAvailabilityService(300), mock(PublicIpLocationService.class));
        ReflectionTestUtils.setField(controller, "discoveryPort", 8080);
        return controller;
    }
}
