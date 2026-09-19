package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.RegistrationException;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.PreflightResult;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.example.privacy.PrivacyJobSpecResolver.ResolvedSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyComputeServiceFixedNodeReplicaTest {
    private PrivacyComputeMapper mapper;
    private PrivacyTemplateCatalog catalog;
    private PrivacyProviderRegistry providers;
    private PrivacyJobSpecResolver resolver;
    private PrivacyComputeProvider provider;
    private PrivacyInputStagingService staging;
    private PrivacyComputeService service;
    private JobSpec request;
    private JobSpec resolvedSpec;

    @BeforeEach
    void setUp() {
        mapper = mock(PrivacyComputeMapper.class);
        catalog = mock(PrivacyTemplateCatalog.class);
        providers = mock(PrivacyProviderRegistry.class);
        resolver = mock(PrivacyJobSpecResolver.class);
        provider = mock(PrivacyComputeProvider.class);
        staging = mock(PrivacyInputStagingService.class);
        service = new PrivacyComputeService(mapper, catalog, providers, resolver,
                mock(PrivacyPartyAuthenticator.class), staging, new ObjectMapper(), Runnable::run);

        request = new JobSpec();
        request.setTemplateId("secure-sum-3p-v1");
        resolvedSpec = new JobSpec();
        resolvedSpec.setTemplateId("secure-sum-3p-v1");

        TemplateDefinition template = new TemplateDefinition();
        template.setTemplateId("secure-sum-3p-v1");
        template.setProvider(ProviderType.MP_SPDZ);
        template.setAvailable(true);
        when(catalog.find("secure-sum-3p-v1")).thenReturn(template);
        when(providers.require(ProviderType.MP_SPDZ)).thenReturn(provider);
        when(resolver.resolve(request, template, "A")).thenReturn(new ResolvedSpec(
                resolvedSpec, Collections.emptyList(), "{}", repeat('d', 64)));

        doThrow(RegistrationException.conflict("PRIVACY_FIXED_NODE_REPLICA_UNAVAILABLE",
                "party A has no usable frozen-version replica on fixed node alibj"))
                .when(staging).validateFixedNodeReplicas(resolvedSpec);
    }

    @Test
    void preflightReportsMissingReplicaOnFixedPartyNode() {
        PreflightResult result = service.preflight(request, "A");

        assertFalse(result.isValid());
        assertEquals(Collections.singletonList(
                "party A has no usable frozen-version replica on fixed node alibj"), result.getErrors());
        verify(staging).validateFixedNodeReplicas(resolvedSpec);
    }

    @Test
    void createRejectsBeforePersistingWhenFixedPartyNodeHasNoReplica() {
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.create(request, "request-1", "A"));

        assertEquals("PRIVACY_FIXED_NODE_REPLICA_UNAVAILABLE", error.getErrorCode());
        verify(staging).validateFixedNodeReplicas(resolvedSpec);
        verify(provider, never()).capability();
        verify(mapper, never()).insertJob(any());
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
