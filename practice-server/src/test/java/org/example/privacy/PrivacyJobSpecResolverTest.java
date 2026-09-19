package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.DatasetMetadata;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivacyJobSpecResolverTest {
    private DatasetRegistrationMapper datasets;
    private PrivacyJobSpecResolver resolver;
    private final String authority = repeat('a', 64);

    @BeforeEach
    void setUp() {
        datasets = mock(DatasetRegistrationMapper.class);
        resolver = new PrivacyJobSpecResolver(datasets, new ObjectMapper());
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(42L).datasetCode("party-data").datasetVersion("v1")
                .status("ACTIVE").build();
        DatasetMetadata metadata = DatasetMetadata.builder()
                .datasetId(42L).authoritativeSizeBytes(123L).digestAlgorithm("SHA-256")
                .digestValue(authority)
                .schemaJson("{\"columns\":[{\"name\":\"value\"},{\"name\":\"label\"}]}")
                .build();
        when(datasets.findDatasetById(42L)).thenReturn(dataset);
        when(datasets.findDatasetMetadata(42L)).thenReturn(metadata);
    }

    @Test
    void freezesCatalogAuthorityAndSchemaInsteadOfTrustingRequestDigest() {
        PrivacyJobSpecResolver.ResolvedSpec result = resolver.resolve(request(null), template(), "A");

        ParticipantSpec participant = result.getSpec().getParticipants().get(0);
        assertEquals(authority, participant.getDatasetSha256());
        assertEquals(123L, participant.getAuthoritativeSizeBytes());
        assertNotNull(participant.getSchemaDigest());
        assertEquals("value", participant.getFrozenSchema().get(0).get("name"));
        assertTrue(result.getSnapshots().get(0).getSchemaJson().startsWith("["));
        assertEquals("2080404369ce502a39199a925f782a67b2f7257a92131748c7926058a95732a8",
                participant.getSchemaDigest());
        assertEquals(participant.getSchemaDigest(), result.getSnapshots().get(0).getSchemaDigest());
        assertEquals("A", result.getSpec().getResultRecipients().get(0));
    }

    @Test
    void rejectsStaleClientDigestAndUnknownSchemaField() {
        assertThrows(RegistrationException.class, () -> resolver.resolve(request(repeat('b', 64)),
                template(), "A"));
        JobSpec unknown = request(null);
        unknown.getParticipants().get(1).setFields(Collections.singletonList("missing"));
        assertThrows(RegistrationException.class, () -> resolver.resolve(unknown, template(), "A"));
    }

    @Test
    void preflightNormalizesFixedThresholdPolicyAndRejectsProtocolDrift() {
        JobSpec valid = request(null);
        valid.setTemplateId("private-threshold-3p-v1");
        PrivacyJobSpecResolver.ResolvedSpec resolved = resolver.resolve(valid,
                template("private-threshold-3p-v1", "MALICIOUS_3PC_HONEST_MAJORITY",
                        "A", "PARTY", "B", "PARTY", "C", "PARTY"), "A");

        assertEquals("topic4_private_threshold_100",
                resolved.getSpec().getEnginePolicy().get("programId"));
        assertEquals(100, resolved.getSpec().getEnginePolicy().get("threshold"));
        assertEquals(1, resolved.getSpec().getEnginePolicy().get("scale"));

        valid.setEnginePolicy(Collections.<String, Object>singletonMap("threshold", 101));
        assertThrows(RegistrationException.class, () -> resolver.resolve(valid,
                template("private-threshold-3p-v1", "MALICIOUS_3PC_HONEST_MAJORITY",
                        "A", "PARTY", "B", "PARTY", "C", "PARTY"), "A"));

        JobSpec stats = request(null);
        stats.setTemplateId("private-stats-3p-v1");
        PrivacyJobSpecResolver.ResolvedSpec normalizedStats = resolver.resolve(stats,
                template("private-stats-3p-v1", "MALICIOUS_3PC_HONEST_MAJORITY",
                        "A", "PARTY", "B", "PARTY", "C", "PARTY"), "A");
        assertEquals(4, normalizedStats.getSpec().getEnginePolicy().get("scale"));
        stats.setEnginePolicy(Collections.<String, Object>singletonMap("scale", 9));
        assertThrows(RegistrationException.class, () -> resolver.resolve(stats,
                template("private-stats-3p-v1", "MALICIOUS_3PC_HONEST_MAJORITY",
                        "A", "PARTY", "B", "PARTY", "C", "PARTY"), "A"));
    }

    @Test
    void preflightEnforcesPsiModeAndPirClientRecipient() {
        JobSpec psi = requestFor("psi-2p-v1", "A", "RECEIVER", "B", "PROVIDER");
        psi.setResultRecipients(Arrays.asList("A", "B"));
        Map<String, Object> psiPolicy = new LinkedHashMap<>();
        psiPolicy.put("keyColumns", Collections.singletonList("value"));
        psiPolicy.put("outputMode", "RECEIVER_ONLY");
        psi.setEnginePolicy(psiPolicy);
        assertThrows(RegistrationException.class, () -> resolver.resolve(psi,
                template("psi-2p-v1", "SEMI_HONEST", "A", "RECEIVER", "B", "PROVIDER"), "A"));

        JobSpec pir = requestFor("pir-keyword-2p-v1", "A", "CLIENT", "B", "SERVER");
        pir.setResultRecipients(Collections.singletonList("B"));
        assertThrows(RegistrationException.class, () -> resolver.resolve(pir,
                template("pir-keyword-2p-v1", "SEMI_HONEST_PIR", "A", "CLIENT", "B", "SERVER"), "A"));

        JobSpec tooManyPirValues = requestFor("pir-keyword-2p-v1", "A", "CLIENT", "B", "SERVER");
        tooManyPirValues.getParticipants().get(1).setFields(Arrays.asList("value", "label"));
        Map<String, Object> pirPolicy = new LinkedHashMap<>();
        pirPolicy.put("queryColumn", "value");
        pirPolicy.put("valueColumns", Arrays.asList("value", "label"));
        tooManyPirValues.setEnginePolicy(pirPolicy);
        assertThrows(RegistrationException.class, () -> resolver.resolve(tooManyPirValues,
                template("pir-keyword-2p-v1", "SEMI_HONEST_PIR", "A", "CLIENT", "B", "SERVER"), "A"));
    }

    @Test
    void preflightRejectsUnsupportedHeAndFederatedTrainingValues() {
        JobSpec he = requestFor("he-paillier-2p-v1", "A", "KEY_HOLDER", "B", "DATA_HOLDER");
        he.setEnginePolicy(Collections.<String, Object>singletonMap("operation", "FHE_BOOTSTRAP"));
        assertThrows(RegistrationException.class, () -> resolver.resolve(he,
                template("he-paillier-2p-v1", "SEMI_HONEST_HE",
                        "A", "KEY_HOLDER", "B", "DATA_HOLDER"), "A"));

        JobSpec hfl = requestFor("hfl-fedavg-logreg-3p-v1",
                "A", "TRAINER", "B", "TRAINER", "C", "TRAINER");
        hfl.getParticipants().forEach(item -> item.setFields(Arrays.asList("value", "label")));
        hfl.setEnginePolicy(Collections.<String, Object>singletonMap("epochs", 6));
        assertThrows(RegistrationException.class, () -> resolver.resolve(hfl,
                template("hfl-fedavg-logreg-3p-v1", "SEMI_HONEST_FL",
                        "A", "TRAINER", "B", "TRAINER", "C", "TRAINER"), "A"));
    }

    private JobSpec request(String digest) {
        JobSpec value = new JobSpec();
        value.setTemplateId("secure-sum-3p-v1");
        for (String party : Arrays.asList("A", "B", "C")) {
            ParticipantSpec participant = new ParticipantSpec();
            participant.setPartyId(party);
            participant.setRole("PARTY");
            participant.setDatasetId("42");
            participant.setDatasetVersion("v1");
            participant.setDatasetSha256(digest);
            participant.setFields(Collections.singletonList("value"));
            value.getParticipants().add(participant);
        }
        return value;
    }

    private JobSpec requestFor(String templateId, String... partyRoles) {
        JobSpec value = new JobSpec();
        value.setTemplateId(templateId);
        for (int i = 0; i < partyRoles.length; i += 2) {
            ParticipantSpec participant = new ParticipantSpec();
            participant.setPartyId(partyRoles[i]);
            participant.setRole(partyRoles[i + 1]);
            participant.setDatasetId("42");
            participant.setDatasetVersion("v1");
            participant.setFields(Collections.singletonList("value"));
            value.getParticipants().add(participant);
        }
        return value;
    }

    private TemplateDefinition template() {
        return template("secure-sum-3p-v1", "MALICIOUS_3PC_HONEST_MAJORITY",
                "A", "PARTY", "B", "PARTY", "C", "PARTY");
    }

    private TemplateDefinition template(String id, String security, String... partyRoles) {
        TemplateDefinition value = new TemplateDefinition();
        value.setTemplateId(id);
        value.setProvider(id.startsWith("private-") || id.startsWith("secure-")
                ? ProviderType.MP_SPDZ : ProviderType.KUSCIA_SECRETFLOW);
        value.setSecurityProfile(security);
        value.setMaxTimeoutSeconds(1800);
        LinkedHashMap<String, String> roles = new LinkedHashMap<>();
        for (int i = 0; i < partyRoles.length; i += 2) roles.put(partyRoles[i], partyRoles[i + 1]);
        value.setRequiredRoles(roles);
        value.setParticipantCount(roles.size());
        return value;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
