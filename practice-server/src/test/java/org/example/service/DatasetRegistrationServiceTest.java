package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisterDatasetRequest;
import org.example.entity.DatasetDiscoveryCandidate;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
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

class DatasetRegistrationServiceTest {
    private DatasetRegistrationMapper mapper;
    private NodeManagementMapper nodeMapper;
    private RestTemplate restTemplate;
    private DatasetRegistrationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DatasetRegistrationMapper.class);
        nodeMapper = mock(NodeManagementMapper.class);
        restTemplate = mock(RestTemplate.class);
        service = new DatasetRegistrationService(mapper, nodeMapper, mock(RuntimeImageMapper.class),
                mock(RegistrationAuditMapper.class), new ObjectMapper(), restTemplate, 8080);
    }

    @Test
    void discoveryUsesVerifiedStorageNodesEvenWhenSchedulingIsDisabled() {
        NodeManagement storage = NodeManagement.builder().nodeId(1).nodeName("storage-1")
                .internalIp("10.0.0.1").type("storage").registrationStatus("REGISTERED")
                .enabled(false).verifiedAt(LocalDateTime.now()).build();
        NodeManagement compute = NodeManagement.builder().nodeId(2).nodeName("compute-1")
                .internalIp("10.0.0.2").type("compute").registrationStatus("REGISTERED")
                .enabled(false).verifiedAt(LocalDateTime.now()).build();
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
}
