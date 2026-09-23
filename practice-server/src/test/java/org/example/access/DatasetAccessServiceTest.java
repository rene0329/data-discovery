package org.example.access;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.mapper.DatasetAccessEventMapper;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.service.DataTransferAddressResolver;
import org.example.service.DatasetHeatService;
import org.example.service.DatasetReplicaAvailabilityService;
import org.example.service.NetworkTopologyService;
import org.example.service.ReplicaAvailability;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.AccessScope;
import org.example.security.access.DatasetAccessAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatasetAccessServiceTest {
    DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
    NodeManagementMapper nodes = mock(NodeManagementMapper.class);
    DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
    NetworkTopologyService topology = mock(NetworkTopologyService.class);
    DatasetAccessEventMapper events = mock(DatasetAccessEventMapper.class);
    DatasetHeatService heat = mock(DatasetHeatService.class);
    NodeDatasetReadClient reads = mock(NodeDatasetReadClient.class);
    DatasetAccessAuthorizationService authorization = mock(DatasetAccessAuthorizationService.class);
    DatasetAccessService service;
    DatasetReplica replica;

    @BeforeEach
    void setup() {
        service = new DatasetAccessService(datasets, nodes, availability, topology, events, heat, reads,
                authorization, new DataTransferAddressResolver(""), 8080);
        when(datasets.findDatasetById(9L)).thenReturn(RegisteredDataset.builder().datasetId(9L)
                .datasetVersion("1.0").status("ACTIVE").build());
        replica = DatasetReplica.builder().replicaId(19L).datasetId(9L).nodeId(2)
                .filePath("/dataset/test.bin").sizeBytes(8L)
                .checksum(repeat("ab", 32)).availability("AVAILABLE").build();
        when(datasets.listReplicas(9L)).thenReturn(Collections.singletonList(replica));
        when(availability.evaluate(replica)).thenReturn(new ReplicaAvailability("USABLE", true, null));
        NodeManagement consumer = NodeManagement.builder().nodeId(1).type("compute-storage")
                .nodeName("master-88").internalIp("10.0.0.1").build();
        NodeManagement source = NodeManagement.builder().nodeId(2).type("storage")
                .nodeName("master-89").internalIp("10.0.0.2").build();
        when(nodes.getNodeById(1)).thenReturn(consumer);
        when(nodes.getNodeById(2)).thenReturn(source);
        when(topology.pathsFrom(1)).thenReturn(Collections.singletonMap(2,
                new NetworkTopologyService.NetworkPath(Arrays.asList(1, 2), 5, 100)));
        when(authorization.authorizeAndIssue(any(), any(), any())).thenAnswer(invocation -> {
            AccessAuthorizationResult result = new AccessAuthorizationResult();
            result.setToken("scoped-token"); result.setScope(invocation.getArgument(1, AccessScope.class));
            return result;
        });
        when(authorization.issueInternal(any(), any())).thenAnswer(invocation -> {
            AccessAuthorizationResult result = new AccessAuthorizationResult();
            result.setToken("consumer-token"); result.setScope(invocation.getArgument(0, AccessScope.class));
            return result;
        });
        doAnswer(invocation -> {
            DatasetAccessEvent event = invocation.getArgument(0);
            event.setEventId(100L);
            return 1;
        }).when(events).insertStarted(any());
    }

    @Test
    void raisesHeatOnlyAfterCompleteVerifiedRead() {
        NodeReadOutcome outcome = new NodeReadOutcome();
        outcome.setLayer("REMOTE_STORAGE"); outcome.setBytesRead(8); outcome.setDurationMs(12);
        outcome.setFirstByteMs(5); outcome.setChecksum(replica.getChecksum());
        when(reads.read(any(), any(), any())).thenReturn(outcome);
        DatasetAccessEvent completed = DatasetAccessEvent.builder().requestId("read-1").success(true).build();
        when(events.findByRequestId("read-1")).thenReturn(null, completed);
        DatasetAccessRequest request = request("read-1");

        assertTrue(service.read(9L, request, "Basic credentials", "127.0.0.1").getSuccess());
        verify(heat).recordAccess(9L);
        verify(events).complete(argThat(event -> event.getBytesRead() == 8
                && "REMOTE_STORAGE".equals(event.getCacheLayer())));
    }

    @Test
    void recordsFailureWithoutRaisingHeat() {
        when(events.findByRequestId("read-2")).thenReturn(null);
        when(reads.read(any(), any(), any())).thenThrow(new RuntimeException("interrupted"));

        assertThrows(RuntimeException.class, () -> service.read(9L, request("read-2"), "Basic credentials", null));
        verifyNoInteractions(heat);
        verify(events).fail(argThat(event -> event.getFailureReason().contains("interrupted")));
    }

    private DatasetAccessRequest request(String id) {
        DatasetAccessRequest request = new DatasetAccessRequest();
        request.setRequestId(id); request.setRunId("run-1"); request.setConsumerNodeId(1);
        return request;
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        while (count-- > 0) out.append(value);
        return out.toString();
    }
}
