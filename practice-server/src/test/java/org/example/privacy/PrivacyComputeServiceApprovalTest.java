package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.RegistrationException;
import org.example.privacy.PrivacyComputeModels.ApprovalRecord;
import org.example.privacy.PrivacyComputeModels.DecisionRequest;
import org.example.privacy.PrivacyComputeModels.JobRecord;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.JobStatus;
import org.example.privacy.PrivacyComputeModels.JobView;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyComputeServiceApprovalTest {
    private PrivacyComputeMapper mapper;
    private PrivacyComputeService service;
    private JobRecord job;
    private List<ApprovalRecord> approvals;
    private List<Runnable> submitted;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(PrivacyComputeMapper.class);
        PrivacyTemplateCatalog catalog = mock(PrivacyTemplateCatalog.class);
        PrivacyProviderRegistry providers = mock(PrivacyProviderRegistry.class);
        PrivacyJobSpecResolver resolver = mock(PrivacyJobSpecResolver.class);
        PrivacyInputStagingService staging = mock(PrivacyInputStagingService.class);
        objectMapper = new ObjectMapper();
        submitted = new ArrayList<>();
        Executor executor = submitted::add;
        PrivacyPartyAuthenticator authenticator = new PrivacyPartyAuthenticator(new MockEnvironment()
                .withProperty("privacy-computing.auth.parties.a.secret", "secret-a-123456")
                .withProperty("privacy-computing.auth.parties.b.secret", "secret-b-123456")
                .withProperty("privacy-computing.auth.parties.c.secret", "secret-c-123456"));
        service = new PrivacyComputeService(mapper, catalog, providers, resolver, authenticator,
                staging, objectMapper, executor);

        JobSpec spec = new JobSpec();
        spec.setResultRecipients(Collections.singletonList("A"));
        for (String party : Arrays.asList("A", "B", "C")) {
            ParticipantSpec participant = new ParticipantSpec();
            participant.setPartyId(party);
            participant.setRole("PARTY");
            spec.getParticipants().add(participant);
        }
        job = new JobRecord();
        job.setJobId("pcj-1");
        job.setRequestId("request-0001");
        job.setCurrentAttemptId("pca-1");
        job.setCurrentAttemptNo(1);
        job.setTemplateId("secure-sum-3p-v1");
        job.setProvider("MP_SPDZ");
        job.setSecurityProfile("MALICIOUS_3PC_HONEST_MAJORITY");
        job.setStatus(JobStatus.AWAITING_APPROVAL.name());
        job.setInitiator("A");
        job.setSpecJson(objectMapper.writeValueAsString(spec));
        job.setSpecDigest(repeat('d', 64));
        job.setProtocolVersion("mp-spdz-0.4.3/malicious-rep-ring");
        job.setImageDigest("sha256:" + repeat('e', 64));
        job.setTimeoutSeconds(1800);
        job.setResultRecipientsJson("[\"A\"]");
        approvals = new ArrayList<>();
        for (String party : Arrays.asList("A", "B", "C")) {
            ApprovalRecord item = new ApprovalRecord();
            item.setJobId(job.getJobId());
            item.setAttemptId(job.getCurrentAttemptId());
            item.setParticipantId(party);
            item.setDecision("PENDING");
            approvals.add(item);
        }
        when(mapper.findJob("pcj-1")).thenAnswer(ignored -> job);
        when(mapper.findParticipantIds("pcj-1")).thenReturn(Arrays.asList("A", "B", "C"));
        when(mapper.findApprovals("pcj-1", "pca-1")).thenAnswer(ignored -> approvals);
        when(mapper.decide(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> {
                    String party = invocation.getArgument(2);
                    String decision = invocation.getArgument(3);
                    String signature = invocation.getArgument(5);
                    for (ApprovalRecord item : approvals) {
                        if (party.equals(item.getParticipantId()) && "PENDING".equals(item.getDecision())) {
                            item.setDecision(decision);
                            item.setDecisionSignature(signature);
                            return 1;
                        }
                    }
                    return 0;
                });
        when(mapper.countApprovals("pcj-1", "pca-1")).thenReturn(3);
        when(mapper.countApproved("pcj-1", "pca-1")).thenAnswer(ignored -> (int) approvals.stream()
                .filter(item -> "APPROVED".equals(item.getDecision())).count());
        when(mapper.transition(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String from = invocation.getArgument(2);
            String to = invocation.getArgument(3);
            if (!from.equals(job.getStatus())) return 0;
            job.setStatus(to);
            return 1;
        });
        when(mapper.updateAttemptStatus(anyString(), anyString())).thenReturn(1);
        when(mapper.insertEvent(any())).thenReturn(1);
    }

    @Test
    void queuesOnlyAfterAllThreeAuthenticatedApprovalsAndStoresHmacSignatures() {
        service.approve("pcj-1", "A", decision("A"));
        assertEquals(JobStatus.AWAITING_APPROVAL.name(), job.getStatus());
        assertEquals(0, submitted.size());
        service.approve("pcj-1", "B", decision("B"));
        assertEquals(JobStatus.AWAITING_APPROVAL.name(), job.getStatus());
        assertEquals(0, submitted.size());

        JobView result = service.approve("pcj-1", "C", decision("C"));

        assertEquals(JobStatus.QUEUED, result.getStatus());
        assertEquals(1, submitted.size());
        for (ApprovalRecord approval : approvals) {
            assertEquals("APPROVED", approval.getDecision());
            assertEquals(64, approval.getDecisionSignature().length());
        }
    }

    @Test
    void cannotApproveForAnotherPartyOrReadUnauthorizedResult() {
        assertThrows(RegistrationException.class,
                () -> service.approve("pcj-1", "A", decision("B")));
        verify(mapper, never()).decide(anyString(), anyString(), anyString(), anyString(), any(), anyString());

        job.setStatus(JobStatus.SUCCEEDED.name());
        assertThrows(RegistrationException.class, () -> service.result("pcj-1", "B"));
    }

    @Test
    void nonParticipantCannotReadDetailOrEventsAndListUsesParticipantFilter() {
        when(mapper.findParticipantIds("pcj-1")).thenReturn(Arrays.asList("A", "B"));

        assertThrows(RegistrationException.class, () -> service.getAuthorized("pcj-1", "C"));
        assertThrows(RegistrationException.class, () -> service.eventsAuthorized("pcj-1", "C"));

        when(mapper.listJobsForParticipant("A", null, 50)).thenReturn(Collections.singletonList(job));
        assertEquals(1, service.listAuthorized(null, null, "A").size());
        verify(mapper).listJobsForParticipant("A", null, 50);
    }

    @Test
    void providerEvidenceSanitizerDropsTokensKeysAndRawPayloads() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("status", "SUCCEEDED");
        raw.put("imageDigest", "sha256:" + repeat('a', 64));
        raw.put("token", "must-not-persist");
        raw.put("privateKey", "must-not-persist");
        raw.put("rawInput", Arrays.asList(1, 2, 3));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageDigest", repeat('b', 64));
        message.put("payloadBytes", 128);
        message.put("secret", "must-not-persist");
        raw.put("messages", Collections.singletonList(message));
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("protocol", "RR22");
        party.put("rawInput", Arrays.asList("secret-row"));
        Map<String, Object> parties = new LinkedHashMap<>();
        parties.put("A", party);
        Map<String, Object> engineEvidence = new LinkedHashMap<>();
        engineEvidence.put("parties", parties);
        raw.put("engineEvidence", engineEvidence);

        Map<String, Object> sanitized = service.sanitizeProviderEvidence(raw);

        assertEquals("SUCCEEDED", sanitized.get("status"));
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.containsKey("token"));
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.containsKey("privateKey"));
        org.junit.jupiter.api.Assertions.assertFalse(sanitized.containsKey("rawInput"));
        @SuppressWarnings("unchecked") Map<String, Object> sanitizedMessage =
                (Map<String, Object>) ((List<?>) sanitized.get("messages")).get(0);
        org.junit.jupiter.api.Assertions.assertFalse(sanitizedMessage.containsKey("secret"));
        assertEquals(128, sanitizedMessage.get("payloadBytes"));
        @SuppressWarnings("unchecked") Map<String, Object> safeEngine =
                (Map<String, Object>) sanitized.get("engineEvidence");
        @SuppressWarnings("unchecked") Map<String, Object> safeParties =
                (Map<String, Object>) safeEngine.get("parties");
        @SuppressWarnings("unchecked") Map<String, Object> safeParty =
                (Map<String, Object>) safeParties.get("A");
        assertEquals("RR22", safeParty.get("protocol"));
        org.junit.jupiter.api.Assertions.assertFalse(safeParty.containsKey("rawInput"));
    }

    private DecisionRequest decision(String party) {
        DecisionRequest request = new DecisionRequest();
        request.setParticipantId(party);
        return request;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
