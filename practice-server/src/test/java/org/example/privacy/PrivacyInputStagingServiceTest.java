package org.example.privacy;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.StagingInput;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.DatasetAccessAuthorizationService;
import org.example.service.DatasetReplicaAvailabilityService;
import org.example.service.ReplicaAvailability;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PrivacyInputStagingServiceTest {
    @Test
    void selectsOnlyReplicaMatchingFrozenAuthorityAndMintsScopedTokenAtDispatch() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
        DatasetAccessAuthorizationService access = mock(DatasetAccessAuthorizationService.class);
        String digest = repeat('a', 64);
        // Both replicas match the frozen bytes. Party A must still use only its
        // deployment-owned Agent node instead of the first replica by node id.
        DatasetReplica wrong = replica(1L, 1, digest);
        DatasetReplica correct = replica(2L, 2, digest);
        when(datasets.listReplicas(42L)).thenReturn(java.util.Arrays.asList(wrong, correct));
        when(availability.evaluate(any())).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodes.getNodeById(1)).thenReturn(NodeManagement.builder()
                .nodeId(1).nodeName("master-88").internalIp("10.0.0.1").build());
        when(nodes.getNodeById(2)).thenReturn(NodeManagement.builder()
                .nodeId(2).nodeName("master-89").internalIp("10.0.0.2").build());
        AccessAuthorizationResult token = new AccessAuthorizationResult();
        token.setTokenType("Topic4Scope");
        token.setToken("one-attempt-token");
        token.setExpiresAt(Instant.parse("2026-09-19T12:00:00Z"));
        when(access.issueInternalOneTime(any(), any())).thenReturn(token);

        PrivacyInputStagingService service = new PrivacyInputStagingService(
                datasets, nodes, availability, access, 8080,
                "master-89", "master-90", "master-91");
        JobSpec spec = new JobSpec();
        ParticipantSpec participant = new ParticipantSpec();
        participant.setPartyId("A");
        participant.setDatasetId("42");
        participant.setDatasetVersion("v1");
        participant.setDatasetSha256(digest);
        participant.setAuthoritativeSizeBytes(123L);
        participant.setSchemaDigest(repeat('c', 64));
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", "value");
        participant.setFrozenSchema(Collections.singletonList(column));
        spec.setParticipants(Collections.singletonList(participant));

        List<StagingInput> result = service.prepare("pcj-1", "pca-1", spec);

        assertEquals(1, result.size());
        assertEquals("master-89", result.get(0).getNodeName());
        assertEquals("/dataset/value.csv", result.get(0).getSourcePath());
        assertEquals("one-attempt-token", result.get(0).getToken());
        assertEquals("sha256:" + digest, result.get(0).getExpectedSha256());
        assertEquals("sha256:" + repeat('c', 64), result.get(0).getExpectedSchemaDigest());
        assertEquals("value", result.get(0).getExpectedSchema().get(0).get("name"));
        verify(access).issueInternalOneTime(any(), any());
    }

    @Test
    void acceptsVerifiedReplicaOutsideFormerFixedSlotNode() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
        DatasetAccessAuthorizationService access = mock(DatasetAccessAuthorizationService.class);
        String digest = repeat('a', 64);
        DatasetReplica otherDomain = replica(1L, 1, digest);
        DatasetReplica fixedButUnavailable = replica(2L, 2, digest);
        when(datasets.listReplicas(42L)).thenReturn(java.util.Arrays.asList(otherDomain, fixedButUnavailable));
        when(nodes.getNodeById(1)).thenReturn(NodeManagement.builder()
                .nodeId(1).nodeName("alish").internalIp("10.0.0.1").build());
        when(nodes.getNodeById(2)).thenReturn(NodeManagement.builder()
                .nodeId(2).nodeName("alibj").internalIp("10.0.0.2").build());
        when(availability.evaluate(otherDomain)).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(availability.evaluate(fixedButUnavailable))
                .thenReturn(new ReplicaAvailability("NODE_UNAVAILABLE", false, "node is unavailable"));

        PrivacyInputStagingService service = new PrivacyInputStagingService(
                datasets, nodes, availability, access, 8080,
                "alibj", "alihz", "alish");
        JobSpec spec = spec("A", digest);

        service.validateAvailableReplicas(spec);
        verify(access, never()).issueInternalOneTime(any(), any());
    }

    @Test
    void validatesAllRuntimeSlotsAgainstAvailableVerifiedReplicas() {
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        DatasetReplicaAvailabilityService availability = mock(DatasetReplicaAvailabilityService.class);
        DatasetAccessAuthorizationService access = mock(DatasetAccessAuthorizationService.class);
        String digest = repeat('a', 64);
        DatasetReplica replicaA = replica(1L, 6, digest);
        DatasetReplica replicaB = replica(2L, 4, digest);
        DatasetReplica replicaC = replica(3L, 5, digest);
        when(datasets.listReplicas(42L)).thenReturn(java.util.Arrays.asList(replicaA, replicaB, replicaC));
        when(availability.evaluate(any())).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodes.getNodeById(6)).thenReturn(NodeManagement.builder().nodeId(6).nodeName("alibj").build());
        when(nodes.getNodeById(4)).thenReturn(NodeManagement.builder().nodeId(4).nodeName("alihz").build());
        when(nodes.getNodeById(5)).thenReturn(NodeManagement.builder().nodeId(5).nodeName("alish").build());

        PrivacyInputStagingService service = new PrivacyInputStagingService(
                datasets, nodes, availability, access, 8080,
                "alibj", "alihz", "alish");
        JobSpec spec = new JobSpec();
        spec.setParticipants(java.util.Arrays.asList(
                participant("A", digest), participant("B", digest), participant("C", digest)));

        service.validateAvailableReplicas(spec);

        verify(access, never()).issueInternalOneTime(any(), any());
    }

    private JobSpec spec(String party, String digest) {
        JobSpec spec = new JobSpec();
        spec.setParticipants(Collections.singletonList(participant(party, digest)));
        return spec;
    }

    private ParticipantSpec participant(String party, String digest) {
        ParticipantSpec participant = new ParticipantSpec();
        participant.setPartyId(party);
        participant.setDatasetId("42");
        participant.setDatasetVersion("v1");
        participant.setDatasetSha256(digest);
        participant.setAuthoritativeSizeBytes(123L);
        participant.setSchemaDigest(repeat('c', 64));
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", "value");
        participant.setFrozenSchema(Collections.singletonList(column));
        return participant;
    }

    private DatasetReplica replica(long id, int node, String digest) {
        return DatasetReplica.builder().replicaId(id).datasetId(42L).nodeId(node)
                .filePath("/dataset/value.csv").sizeBytes(123L)
                .checksumAlgorithm("SHA-256").checksum(digest).availability("AVAILABLE")
                .verifiedAt(LocalDateTime.now()).build();
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
