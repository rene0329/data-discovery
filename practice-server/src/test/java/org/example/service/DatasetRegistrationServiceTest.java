package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisterDatasetRequest;
import org.example.entity.DatasetDiscoveryCandidate;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class DatasetRegistrationServiceTest {
    private DatasetRegistrationMapper mapper;
    private NodeManagementMapper nodeMapper;
    private RestTemplate restTemplate;
    private DatasetRegistrationService service;
    private DatasetReplicaAvailabilityService replicaAvailabilityService;

    @BeforeEach
    void setUp() {
        mapper = mock(DatasetRegistrationMapper.class);
        nodeMapper = mock(NodeManagementMapper.class);
        restTemplate = mock(RestTemplate.class);
        replicaAvailabilityService = mock(DatasetReplicaAvailabilityService.class);
        service = new DatasetRegistrationService(mapper, nodeMapper, mock(RuntimeImageMapper.class),
                mock(RegistrationAuditMapper.class), new ObjectMapper(), restTemplate,
                replicaAvailabilityService, 8080);
    }

    @Test
    void discoveryUsesVerifiedStorageNodesEvenWhenSchedulingIsDisabled() {
        NodeManagement storage = NodeManagement.builder().nodeId(1).nodeName("storage-1")
                .internalIp("10.0.0.1").type("storage").registrationStatus("REGISTERED")
                .enabled(false).observedStatus("ONLINE").verifiedAt(LocalDateTime.now()).build();
        NodeManagement compute = NodeManagement.builder().nodeId(2).nodeName("compute-1")
                .internalIp("10.0.0.2").type("compute").registrationStatus("REGISTERED")
                .enabled(false).observedStatus("ONLINE").verifiedAt(LocalDateTime.now()).build();
        when(nodeMapper.listRegisteredNodes(null, null, null))
                .thenReturn(Arrays.asList(storage, compute));
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        when(restTemplate.getForEntity(anyString(), org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenReturn(ResponseEntity.ok(response));

        OperationResult result = service.discover(Collections.emptySet());

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1, result.getRequestedCount());
        assertEquals(1, result.getProcessedCount());
    }

    @Test
    void registrationRejectsPhysicalFileAlreadyOwnedByAnotherDataset() {
        RegisterDatasetRequest request = new RegisterDatasetRequest();
        request.setCandidateId(9L);
        request.setDatasetCode("sales");
        request.setName("sales");
        request.setVersion("1.0");
        request.setDataType("NPZ");
        DatasetDiscoveryCandidate candidate = DatasetDiscoveryCandidate.builder()
                .candidateId(9L).nodeId(1).filePath("/dataset/sales.npz")
                .availability("AVAILABLE").build();
        when(mapper.findCandidateById(9L)).thenReturn(candidate);
        when(mapper.findReplicaByNodePath(1, "/dataset/sales.npz"))
                .thenReturn(DatasetReplica.builder().replicaId(3L).datasetId(2L).build());

        assertThrows(RegistrationException.class, () -> service.register(request, "request-1"));
    }

    @Test
    void datasetViewSummarizesReplicaBusinessHealth() {
        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(5L)
                .datasetCode("sales").name("sales").status("ACTIVE").build();
        DatasetReplica usable = DatasetReplica.builder().replicaId(1L).nodeId(1)
                .availability("AVAILABLE").build();
        DatasetReplica unreachable = DatasetReplica.builder().replicaId(2L).nodeId(2)
                .availability("AVAILABLE").build();
        when(mapper.listDatasets("", null)).thenReturn(Collections.singletonList(dataset));
        when(mapper.listReplicas(5L)).thenReturn(Arrays.asList(usable, unreachable));
        doAnswer(invocation -> {
            DatasetReplica replica = invocation.getArgument(0);
            if (replica.getReplicaId().equals(1L)) replica.setEffectiveAvailability("USABLE");
            else {
                replica.setEffectiveAvailability("UNREACHABLE");
                replica.setStatusReason("节点未启用");
            }
            return null;
        }).when(replicaAvailabilityService).enrich(org.mockito.ArgumentMatchers.any(DatasetReplica.class));

        org.example.dto.registration.RegisteredDatasetView view =
                service.listDatasets("", null).get(0);

        assertEquals("DEGRADED", view.getHealthStatus());
        assertEquals(1, view.getAvailableReplicaCount());
        assertEquals(2, view.getTotalReplicaCount());
        assertEquals("节点未启用", view.getStatusReason());
    }
}
