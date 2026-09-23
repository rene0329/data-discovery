package org.example.factory;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.api.model.Container;
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
        NodeManagement hz = node(4, "cluster-hz-1"), bj = node(6, "cluster-bj-1");
        when(nodes.getNodeByName("cluster-hz-1")).thenReturn(hz);
        when(nodes.getNodeByName("cluster-bj-1")).thenReturn(bj);
        when(nodes.selectAllNodes()).thenReturn(Arrays.asList(node(1, "master-141"), node(3, "master-40"), hz, bj));
        when(nodes.getComputeCapableNodes()).thenReturn(Collections.singletonList(bj));
        accessLink = edge(4, 1, 20, 50);
        when(edges.selectAllMetrics()).thenReturn(Arrays.asList(accessLink, edge(1, 3, 8, 60), edge(1, 6, 30, 40)));
        NetworkTopologyService topology = new NetworkTopologyService(edges, nodes, availability, 1800);
        factory = new K8sJobFactory("unused", nodes, mock(TrainingProfileMapper.class), "cluster.local",
                "curl:test", "python:test", "discovery", "default", 8080, "", 1, topology, availability);
        Map<String, KubernetesClient> clients = (Map<String, KubernetesClient>) ReflectionTestUtils.getField(factory, "clusterClients");
        clients.put("cluster-a", mock(KubernetesClient.class));
    }

    @Test
    void automaticSchedulingIncludesMultiHopCandidateWithPathMetrics() {
        List<?> candidates = ReflectionTestUtils.invokeMethod(
                factory, "gatherAvailableNodes", 0.5, 1.0, "cluster-hz-1");
        assertEquals(1, candidates.size());
        assertEquals("cluster-bj-1", ReflectionTestUtils.getField(candidates.get(0), "name"));
        assertEquals(50.0, ((Number) ReflectionTestUtils.getField(candidates.get(0), "latencyMs")).doubleValue());
        assertEquals(40.0, ((Number) ReflectionTestUtils.getField(candidates.get(0), "bandwidthMbps")).doubleValue());
    }

    @Test
    void disconnectedCandidateIsRejectedForAutomaticAndForcedScheduling() {
        accessLink.setStatus("inactive");
        List<?> candidates = ReflectionTestUtils.invokeMethod(
                factory, "gatherAvailableNodes", 0.5, 1.0, "cluster-hz-1");
        assertTrue(candidates.isEmpty());
        assertThrows(RegistrationException.class, () -> factory.createDataProcessingJob(
                "test-job", "cluster-hz-1", "test.npz", "/dataset/test.npz", "cluster-bj-1", null, 0.5, 1.0));
    }

    @Test
    void dataPreparationCommandEmitsMeasuredBytesAndSha256() {
        String command = ReflectionTestUtils.invokeMethod(factory, "buildWgetCommand",
                "/data/test.npz", "http://source/test.npz", "");

        assertNotNull(command);
        assertTrue(command.contains("wc -c"));
        assertTrue(command.contains("sha256sum"));
        assertTrue(command.contains("INPUT_BYTES="));
        assertTrue(command.contains("INPUT_SHA256="));
    }

    @Test
    void scopedReadTokenIsInjectedAsAnEnvironmentVariableAndNotLoggedInCommandText() {
        String token = "signed.secret.token";
        JobCreationResult result = factory.createDataProcessingJob(
                "authorized-job", "cluster-hz-1", "test.npz", "/dataset/test.npz",
                "cluster-bj-1", null, 0.5, 1.0, null, null, token);
        Container transfer = result.getJob().getSpec().getTemplate().getSpec().getInitContainers().get(0);
        String command = transfer.getCommand().get(2);

        assertTrue(command.contains("Authorization: Bearer ${DATASET_ACCESS_TOKEN}"));
        assertFalse(command.contains(token));
        assertEquals(token, transfer.getEnv().stream()
                .filter(env -> "DATASET_ACCESS_TOKEN".equals(env.getName()))
                .findFirst().orElseThrow(AssertionError::new).getValue());
    }

    @Test
    void sameNodeOverrideCreatesTheInPlaceJobOnTheSourceNode() {
        JobCreationResult result = factory.createDataProcessingJob(
                "in-place-job", "cluster-hz-1", "test.npz", "/dataset/test.npz",
                "cluster-hz-1", null, 0.5, 1.0, null, null, "scoped-read-token");

        assertEquals("cluster-hz-1", result.getSelectedNodeName());
        assertEquals("cluster-hz-1", result.getJob().getSpec().getTemplate().getSpec().getNodeName());
    }

    private NodeManagement node(int id, String name) {
        return NodeManagement.builder().nodeId(id).nodeName(name).cluster("cluster-a")
                .internalIp("10.0.0." + id)
                .maxCpu(4.0).maxMemory(8.0).enabled(true).registrationStatus("ACTIVE")
                .observedStatus("ONLINE").lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();
    }

    private EdgeManagement edge(int source, int target, double latency, long bandwidth) {
        return EdgeManagement.builder().sourceId(source).targetId(target).latency(latency).bandwidth(bandwidth)
                .status("active").measurementTime(Timestamp.from(Instant.now())).build();
    }
}
