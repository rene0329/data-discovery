package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.RegistrationException;
import org.example.mapper.ApiIdempotencyMapper;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.JobStatus;
import org.example.privacy.PrivacyComputeModels.JobView;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.security.aggregation.coordinator.SecureAggregationRun;
import org.example.service.ApiIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacySecureAggregationBridgeTest {
    private PrivacyComputeService service;
    private ApiIdempotencyMapper idempotencyMapper;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        service = mock(PrivacyComputeService.class);
        idempotencyMapper = mock(ApiIdempotencyMapper.class);
        when(idempotencyMapper.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(idempotencyMapper.complete(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        environment = new MockEnvironment()
                .withProperty("privacy-computing.auth.parties.a.secret", "secret-a-123456")
                .withProperty("privacy-computing.auth.parties.b.secret", "secret-b-123456")
                .withProperty("privacy-computing.auth.parties.c.secret", "secret-c-123456");
    }

    @Test
    void oldPostShapeCreatesSecureSumJobAndPreservesLegacyResponseShape() {
        for (String party : Arrays.asList("a", "b", "c")) {
            environment.withProperty("privacy-computing.legacy-secure-sum.parties." + party + ".dataset-id",
                    String.valueOf(10 + party.charAt(0) - 'a'))
                    .withProperty("privacy-computing.legacy-secure-sum.parties." + party + ".dataset-version", "v1")
                    .withProperty("privacy-computing.legacy-secure-sum.parties." + party + ".fields", "value");
        }
        JobView created = createdJob();
        when(service.create(org.mockito.ArgumentMatchers.any(JobSpec.class), anyString(), anyString()))
                .thenReturn(created);
        LegacySecureAggregationBridge bridge = bridge();

        SecureAggregationRun response = bridge.start(basic("A", "secret-a-123456"),
                "legacy-request-0001", null);

        ArgumentCaptor<JobSpec> spec = ArgumentCaptor.forClass(JobSpec.class);
        verify(service).create(spec.capture(), org.mockito.ArgumentMatchers.eq("legacy-request-0001"),
                org.mockito.ArgumentMatchers.eq("A"));
        assertEquals("secure-sum-3p-v1", spec.getValue().getTemplateId());
        assertEquals("MALICIOUS_3PC_HONEST_MAJORITY", spec.getValue().getSecurityProfile());
        assertEquals(Collections.singletonList("A"), spec.getValue().getResultRecipients());
        assertEquals(Arrays.asList("A", "B", "C"), Arrays.asList(
                spec.getValue().getParticipants().get(0).getPartyId(),
                spec.getValue().getParticipants().get(1).getPartyId(),
                spec.getValue().getParticipants().get(2).getPartyId()));
        assertEquals("pcj-legacy-1", response.getRunId());
        assertEquals("legacy-request-0001", response.getRequestId());
        assertEquals("PENDING", response.getStatus());
        assertEquals("[\"A\",\"B\",\"C\"]", response.getParticipantsJson());
    }

    @Test
    void absentFixtureMappingFailsExplicitlyWithoutCreatingFallbackJob() {
        RegistrationException failure = assertThrows(RegistrationException.class,
                () -> bridge().start(basic("A", "secret-a-123456"), "legacy-request-0002", null));

        assertEquals("LEGACY_MAPPING_NOT_CONFIGURED", failure.getErrorCode());
        assertEquals(409, failure.getStatus().value());
    }

    @Test
    void callerCannotUseLegacyEndpointToSelectAnotherTemplate() {
        JobSpec supplied = new JobSpec();
        supplied.setTemplateId("private-stats-3p-v1");

        RegistrationException failure = assertThrows(RegistrationException.class,
                () -> bridge().start(basic("A", "secret-a-123456"), "legacy-request-0003", supplied));

        assertEquals("LEGACY_TEMPLATE_INVALID", failure.getErrorCode());
        assertEquals(422, failure.getStatus().value());
    }

    private LegacySecureAggregationBridge bridge() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new LegacySecureAggregationBridge(service,
                new PrivacyPartyAuthenticator(environment),
                new ApiIdempotencyService(idempotencyMapper, objectMapper),
                objectMapper, environment);
    }

    private JobView createdJob() {
        JobView job = new JobView();
        job.setJobId("pcj-legacy-1");
        job.setRequestId("legacy-request-0001");
        job.setStatus(JobStatus.AWAITING_APPROVAL);
        job.setProtocolVersion("mp-spdz-0.4.3/malicious-rep-ring");
        job.setResultRecipients(Collections.singletonList("A"));
        for (String party : Arrays.asList("A", "B", "C")) {
            ParticipantSpec participant = new ParticipantSpec();
            participant.setPartyId(party);
            participant.setRole("PARTY");
            job.getParticipants().add(participant);
        }
        return job;
    }

    private String basic(String party, String secret) {
        String value = party + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
