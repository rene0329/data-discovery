package org.example.service;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplicaReverifyWorkerTest {
    @Test
    void revalidatesOnlyStaleReplicasInsteadOfTheWholeDataset() {
        DatasetRegistrationMapper mapper = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodeMapper = mock(NodeManagementMapper.class);
        NodeAvailabilityService nodeAvailability = mock(NodeAvailabilityService.class);
        DatasetRegistrationService registrationService = mock(DatasetRegistrationService.class);
        ReplicaReverifyWorker worker = new ReplicaReverifyWorker(
                mapper, nodeMapper, nodeAvailability, registrationService);
        ReflectionTestUtils.setField(worker, "maxDatasetsPerTick", 5);
        ReflectionTestUtils.setField(worker, "retryBackoffMinutes", 15L);

        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(19L).status("ACTIVE").build();
        DatasetReplica healthy = DatasetReplica.builder().replicaId(18L).datasetId(19L)
                .nodeId(1).availability("AVAILABLE").build();
        DatasetReplica stale = DatasetReplica.builder().replicaId(24L).datasetId(19L)
                .nodeId(3).availability("UNVERIFIED").build();
        NodeManagement node = NodeManagement.builder().nodeId(3).build();
        when(mapper.listDatasets(null, "ACTIVE")).thenReturn(Collections.singletonList(dataset));
        when(mapper.listReplicas(19L)).thenReturn(Arrays.asList(healthy, stale));
        when(nodeMapper.getNodeById(3)).thenReturn(node);
        when(nodeAvailability.isSchedulable(node)).thenReturn(true);
        when(registrationService.verifyReplica(eq(19L), eq(24L), anyString())).thenReturn(true);

        worker.reverifyStaleReplicas();

        verify(registrationService).verifyReplica(eq(19L), eq(24L), anyString());
        verify(registrationService, never()).verify(eq(19L), anyString());
        verify(registrationService, never()).verifyReplica(eq(19L), eq(18L), anyString());
    }
}
