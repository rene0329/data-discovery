package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.auth.AuthenticatedUser;
import org.example.entity.DatasetMetadata;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.privacy.PrivacyComputeModels.DatasetOwnershipRecord;
import org.example.privacy.PrivacyComputeModels.InputSpec;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivacyDatasetOwnerResolutionTest {
    private DatasetRegistrationMapper datasets;
    private PrivacyComputeMapper privacy;
    private PrivacyJobSpecResolver resolver;

    @BeforeEach
    void setUp() {
        datasets = mock(DatasetRegistrationMapper.class);
        privacy = mock(PrivacyComputeMapper.class);
        resolver = new PrivacyJobSpecResolver(datasets, privacy, new ObjectMapper());
        configureDataset(11L, 101L, "alice", 1L, "finance");
        configureDataset(22L, 202L, "bob", 2L, "research");
    }

    @Test
    void resolvesOwnerAndDomainThenMapsSlotsToRuntimeParties() {
        PrivacyJobSpecResolver.ResolvedSpec resolved = resolver.resolve(spec(), template(), initiator());
        assertEquals("P0", resolved.getSpec().getParticipants().get(0).getSlotId());
        assertEquals("A", resolved.getSpec().getParticipants().get(0).getPartyId());
        assertEquals(Long.valueOf(101L), resolved.getSpec().getParticipants().get(0).getOwnerUserId());
        assertEquals("B", resolved.getSpec().getParticipants().get(1).getPartyId());
        assertEquals(Long.valueOf(2L), resolved.getSpec().getParticipants().get(1).getOwnerDomainId());
        assertEquals(Collections.singletonList("A"), resolved.getSpec().getResultRecipients());
    }

    @Test
    void rejectsTwoInputsFromTheSameBusinessDomain() {
        configureDataset(22L, 202L, "bob", 1L, "finance");
        assertThrows(RegistrationException.class, () -> resolver.resolve(spec(), template(), initiator()));
    }

    private void configureDataset(long datasetId, long ownerId, String owner, long domainId, String domain) {
        when(datasets.findDatasetById(datasetId)).thenReturn(RegisteredDataset.builder()
                .datasetId(datasetId).datasetCode("data-" + datasetId).datasetVersion("v1")
                .status("ACTIVE").build());
        when(datasets.findDatasetMetadata(datasetId)).thenReturn(DatasetMetadata.builder()
                .datasetId(datasetId).authoritativeSizeBytes(10L).digestAlgorithm("SHA-256")
                .digestValue(repeat((char) ('a' + (datasetId % 10)), 64))
                .schemaJson("{\"columns\":[{\"name\":\"id\"}]}").build());
        DatasetOwnershipRecord value = new DatasetOwnershipRecord();
        value.setDatasetId(datasetId);
        value.setOwnerUserId(ownerId);
        value.setOwnerUsername(owner);
        value.setOwnerEnabled(true);
        value.setOwnerDomainId(domainId);
        value.setOwnerDomainCode(domain);
        value.setDomainEnabled(true);
        when(privacy.findDatasetOwnership(datasetId)).thenReturn(value);
    }

    private JobSpec spec() {
        JobSpec value = new JobSpec();
        value.setTemplateId("psi-2p-v1");
        value.getInputs().add(input("P0", 11L));
        value.getInputs().add(input("P1", 22L));
        value.setEnginePolicy(Collections.<String, Object>singletonMap("keyColumns",
                Collections.singletonList("id")));
        return value;
    }

    private InputSpec input(String slot, long datasetId) {
        InputSpec value = new InputSpec();
        value.setSlotId(slot);
        value.setDatasetId(datasetId);
        value.setDatasetVersion("v1");
        value.setFields(Collections.singletonList("id"));
        return value;
    }

    private TemplateDefinition template() {
        TemplateDefinition value = new TemplateDefinition();
        value.setTemplateId("psi-2p-v1");
        value.setProvider(ProviderType.KUSCIA_SECRETFLOW);
        value.setSecurityProfile("SEMI_HONEST");
        value.setMaxTimeoutSeconds(1800);
        LinkedHashMap<String, String> roles = new LinkedHashMap<>();
        roles.put("A", "RECEIVER");
        roles.put("B", "PROVIDER");
        value.setRequiredRoles(roles);
        value.setParticipantCount(2);
        return value;
    }

    private AuthenticatedUser initiator() {
        return new AuthenticatedUser(999L, "starter", "Starter",
                new LinkedHashSet<>(Arrays.asList("DATA_OWNER")), 9L, "starter-domain", "Starter");
    }

    private String repeat(char value, int count) {
        char safe = "0123456789abcdef".indexOf(value) >= 0 ? value : 'a';
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(safe);
        return result.toString();
    }
}
