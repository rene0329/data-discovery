package org.example.service;

import org.example.entity.DatasetReplica;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K8sTaskOrchestratorServiceTest {

    @Test
    void prefersReplicaAlreadyLocatedOnComputeNode() {
        DatasetReplica storageReplica = DatasetReplica.builder()
                .replicaId(19L)
                .nodeId(6)
                .build();
        DatasetReplica computeReplica = DatasetReplica.builder()
                .replicaId(20L)
                .nodeId(3)
                .build();

        DatasetReplica selected = K8sTaskOrchestratorService.selectPreferredSourceReplica(
                Arrays.asList(storageReplica, computeReplica), Collections.singleton(3))
                .orElseThrow(AssertionError::new);

        assertEquals(20L, selected.getReplicaId());
    }

    @Test
    void preservesReplicaOrderWhenNoComputeReplicaExists() {
        DatasetReplica first = DatasetReplica.builder().replicaId(19L).nodeId(6).build();
        DatasetReplica second = DatasetReplica.builder().replicaId(21L).nodeId(4).build();

        DatasetReplica selected = K8sTaskOrchestratorService.selectPreferredSourceReplica(
                Arrays.asList(first, second), Collections.emptySet())
                .orElseThrow(AssertionError::new);

        assertEquals(19L, selected.getReplicaId());
    }
}
