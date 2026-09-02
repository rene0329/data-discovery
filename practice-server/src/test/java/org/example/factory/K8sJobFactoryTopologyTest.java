package org.example.factory;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.entity.EdgeManagement;
import org.example.entity.NodeManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.TrainingProfileMapper;
import org.example.service.NetworkTopologyService;
import org.example.service.NodeAvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class K8sJobFactoryTopologyTest {
    private K8sJobFactory factory;
    private EdgeManagement accessLink;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        EdgeManagementMapper edges = mock(EdgeManagementMapper.class);
        NodeAvailabilityService availability = new NodeAvailabilityService(300);
        NodeManagement hz = node(4, "alihz"), bj = node(6, "alibj");
        when(nodes.getNodeByName("alihz")).thenReturn(hz);
        when(nodes.getNodeByName("alibj")).thenReturn(bj);
        when(nodes.selectAllNodes()).thenReturn(Arrays.asList(node(1, "master-88"), node(3, "master-90"), hz, bj));
        when(nodes.getComputeCapableNodes()).thenReturn(Collections.singletonList(bj));
        accessLink = edge(4, 1, 20, 50);
        when(edges.links()).thenReturn(Arrays.asList(accessLink, edge(1, 3, 8, 60), edge(3, 6, 30, 40)));
        NetworkTopologyService topology = new NetworkTopologyService(edges, nodes, availability, 1800);
        factory = new K8sJobFactory("unused", nodes, mock(TrainingProfileMapper.class), "cluster.local",
                "curl:test", "python:test", "discovery", "default", 8080, "", 1, topology, availability);
        Map<String, KubernetesClient> clients = (Map<String, KubernetesClient>) ReflectionTestUtils.getField(factory, "clusterClients");
        clients.put("cluster-a", mock(KubernetesClient.class));
    }

    @Test
    void automaticSchedulingIncludesMultiHopCandidateWithPathMetrics() {
        List<?> candidates = ReflectionTestUtils.invokeMethod(
                factory, "gatherAvailableNodes", 0.5, 1.0, "alihz");
        assertEquals(1, candidates.size());
        assertEquals("alibj", ReflectionTestUtils.getField(candidates.get(0), "name"));
        assertEquals(58.0, ((Number) ReflectionTestUtils.getField(candidates.get(0), "latencyMs")).doubleValue());
        assertEquals(40.0, ((Number) ReflectionTestUtils.getField(candidates.get(0), "bandwidthMbps")).doubleValue());
    }

    @Test
    void disconnectedCandidateIsRejectedForAutomaticAndForcedScheduling() {
        accessLink.setStatus("inactive");
        List<?> candidates = ReflectionTestUtils.invokeMethod(
                factory, "gatherAvailableNodes", 0.5, 1.0, "alihz");
        assertTrue(candidates.isEmpty());
        assertThrows(RegistrationException.class, () -> factory.createDataProcessingJob(
                "test-job", "alihz", "test.npz", "/dataset/test.npz", "alibj", null, 0.5, 1.0));
    }

    private NodeManagement node(int id, String name) {
        return NodeManagement.builder().nodeId(id).nodeName(name).cluster("cluster-a")
                .maxCpu(4.0).maxMemory(8.0).enabled(true).registrationStatus("ACTIVE")
                .observedStatus("ONLINE").lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    private EdgeManagement edge(int source, int target, double latency, long bandwidth) {
        return EdgeManagement.builder().sourceId(source).targetId(target).latency(latency).bandwidth(bandwidth)
                .status("active").measurementTime(Timestamp.from(Instant.now())).build();
    }
}
