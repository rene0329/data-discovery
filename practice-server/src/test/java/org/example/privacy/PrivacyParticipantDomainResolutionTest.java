package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.auth.AuthenticatedUser;
import org.example.entity.DatasetMetadata;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.privacy.PrivacyComputeModels.InputSpec;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.PreflightResult;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.example.security.access.DatasetDomainLocation;
import org.example.security.access.DatasetDomainMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A privacy input's party is the single enabled domain its dataset is located in. */
class PrivacyParticipantDomainResolutionTest {
    private DatasetRegistrationMapper datasets;
    private DatasetDomainMapper locations;
    private PrivacyJobSpecResolver resolver;

    @BeforeEach
    void setUp() {
        datasets = mock(DatasetRegistrationMapper.class);
        locations = mock(DatasetDomainMapper.class);
        resolver = new PrivacyJobSpecResolver(datasets, locations, new ObjectMapper());
        configureDataset(11L);
        configureDataset(22L);
        locate(11L, domain(11L, 1L, "domain-a", "上海域（A）"));
        locate(22L, domain(22L, 2L, "domain-b", "深圳域（B）"));
    }

    @Test
    void eachInputIsPartOfTheSingleDomainItsDatasetIsLocatedIn() {
        PrivacyJobSpecResolver.ResolvedSpec resolved = resolver.resolve(spec(), template(), initiator());

        ParticipantSpec a = resolved.getSpec().getParticipants().get(0);
        assertEquals("P0", a.getSlotId());
        assertEquals("A", a.getPartyId());
        assertEquals(Long.valueOf(1L), a.getOwnerDomainId());
        assertEquals("domain-a", a.getOwnerDomainCode());
        assertEquals("上海域（A）", a.getOwnerDomainName());
        ParticipantSpec b = resolved.getSpec().getParticipants().get(1);
        assertEquals("B", b.getPartyId());
        assertEquals(Long.valueOf(2L), b.getOwnerDomainId());
        assertEquals("深圳域（B）", b.getOwnerDomainName());
        assertEquals(Long.valueOf(1L), resolved.getSnapshots().get(0).getOwnerDomainId());
        assertEquals(Long.valueOf(2L), resolved.getSnapshots().get(1).getOwnerDomainId());
        assertEquals(Collections.singletonList("A"), resolved.getSpec().getResultRecipients());
        // The frozen spec carries domains only, never the retired holder.
        assertFalse(resolved.getSpecJson().contains("ownerUserId"), resolved.getSpecJson());
        assertTrue(resolved.getSpecJson().contains("\"ownerDomainId\":1"), resolved.getSpecJson());
    }

    @Test
    void theDomainFollowsTheDatasetWhenItMoves() {
        locate(11L, domain(11L, 3L, "domain-c", "北京域（C）"));

        ParticipantSpec a = resolver.resolve(spec(), template(), initiator()).getSpec().getParticipants().get(0);

        assertEquals(Long.valueOf(3L), a.getOwnerDomainId());
    }

    @Test
    void datasetLocatedInNoEnabledDomainIsRejected() {
        locate(22L);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> resolver.resolve(spec(), template(), initiator()));

        assertEquals("PARTICIPANT_DOMAIN_MISSING", error.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("dataset 22"), error.getMessage());
    }

    @Test
    void datasetLocatedInSeveralDomainsIsRejected() {
        locate(22L, domain(22L, 2L, "domain-b", "深圳域（B）"), domain(22L, 3L, "domain-c", "北京域（C）"));

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> resolver.resolve(spec(), template(), initiator()));

        assertEquals("PARTICIPANT_DOMAIN_AMBIGUOUS", error.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("深圳域（B）, 北京域（C）"), error.getMessage());
    }

    @Test
    void twoInputsFromTheSameDomainAreRejected() {
        locate(22L, domain(22L, 1L, "domain-a", "上海域（A）"));

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> resolver.resolve(spec(), template(), initiator()));

        assertEquals("PARTICIPANT_DOMAIN_DUPLICATE", error.getErrorCode());
    }

    @Test
    void preflightReportsAndCreateRejectsAnUnresolvableDomain() {
        locate(22L, domain(22L, 2L, "domain-b", "深圳域（B）"), domain(22L, 3L, "domain-c", "北京域（C）"));
        PrivacyComputeMapper mapper = mock(PrivacyComputeMapper.class);
        PrivacyTemplateCatalog catalog = mock(PrivacyTemplateCatalog.class);
        PrivacyProviderRegistry providers = mock(PrivacyProviderRegistry.class);
        TemplateDefinition template = template();
        template.setAvailable(true);
        JobSpec request = spec();
        request.setTemplateId(template.getTemplateId());
        when(catalog.find(template.getTemplateId())).thenReturn(template);
        when(providers.require(any())).thenReturn(mock(PrivacyComputeProvider.class));
        PrivacyComputeService service = new PrivacyComputeService(mapper, catalog, providers, resolver,
                mock(PrivacyInputStagingService.class), mock(PrivacyApprovalSigner.class),
                new ObjectMapper(), Runnable::run);

        PreflightResult preflight = service.preflight(request, initiator());
        assertFalse(preflight.isValid());
        assertEquals(1, preflight.getErrors().size());
        assertTrue(preflight.getErrors().get(0).contains("located in 2 collaboration domains"),
                preflight.getErrors().toString());

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.create(request, "request-1", initiator()));
        assertEquals("PARTICIPANT_DOMAIN_AMBIGUOUS", error.getErrorCode());
        verify(mapper, never()).insertJob(any());
    }

    private void configureDataset(long datasetId) {
        when(datasets.findDatasetById(datasetId)).thenReturn(RegisteredDataset.builder()
                .datasetId(datasetId).datasetCode("data-" + datasetId).datasetVersion("v1")
                .status("ACTIVE").build());
        when(datasets.findDatasetMetadata(datasetId)).thenReturn(DatasetMetadata.builder()
                .datasetId(datasetId).authoritativeSizeBytes(10L).digestAlgorithm("SHA-256")
                .digestValue(repeat((char) ('a' + (datasetId % 10)), 64))
                .schemaJson("{\"columns\":[{\"name\":\"id\"}]}").build());
    }

    private void locate(long datasetId, DatasetDomainLocation... rows) {
        when(locations.findLocationDomains(Collections.singletonList(datasetId)))
                .thenReturn(Arrays.asList(rows));
    }

    private static DatasetDomainLocation domain(long datasetId, long domainId, String code, String name) {
        return new DatasetDomainLocation(datasetId, domainId, code, name);
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
