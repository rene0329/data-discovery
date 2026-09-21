package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.auth.AuthenticatedUser;
import org.example.exception.RegistrationException;
import org.example.privacy.PrivacyComputeModels.ApprovalRecord;
import org.example.privacy.PrivacyComputeModels.CapabilityStatus;
import org.example.privacy.PrivacyComputeModels.DecisionRequest;
import org.example.privacy.PrivacyComputeModels.InputSnapshotRecord;
import org.example.privacy.PrivacyComputeModels.JobRecord;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.JobStatus;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.example.privacy.PrivacyJobSpecResolver.ResolvedSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyComputeServiceApprovalTest {
    private PrivacyComputeMapper mapper;
    private PrivacyTemplateCatalog catalog;
    private PrivacyProviderRegistry providers;
    private PrivacyJobSpecResolver resolver;
    private PrivacyComputeProvider provider;
    private PrivacyInputStagingService staging;
    private PrivacyApprovalSigner approvalSigner;
    private PrivacyComputeService service;
    private ObjectMapper json;
    private List<Runnable> submitted;
    private JobRecord job;
    private List<ApprovalRecord> approvals;
    private List<ParticipantSpec> participants;
    private List<InputSnapshotRecord> snapshots;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(PrivacyComputeMapper.class);
        catalog = mock(PrivacyTemplateCatalog.class);
        providers = mock(PrivacyProviderRegistry.class);
        resolver = mock(PrivacyJobSpecResolver.class);
        provider = mock(PrivacyComputeProvider.class);
        staging = mock(PrivacyInputStagingService.class);
        approvalSigner = mock(PrivacyApprovalSigner.class);
        json = new ObjectMapper();
        submitted = new ArrayList<>();
        Executor executor = submitted::add;
        service = new PrivacyComputeService(mapper, catalog, providers, resolver, staging,
                approvalSigner, json, executor);

        participants = Arrays.asList(participant("A", 1L, "alice", 11L),
                participant("B", 2L, "bob", 22L));
        snapshots = Arrays.asList(snapshot("A", 101L), snapshot("B", 202L));
        approvals = new ArrayList<>();
        approvals.add(approval("A", 1L, "APPROVED"));
        approvals.add(approval("B", 2L, "PENDING"));
        job = job(JobStatus.AWAITING_APPROVAL, "pca-1", 1);

        when(mapper.findJob("pcj-1")).thenAnswer(ignored -> job);
        when(mapper.findParticipants("pcj-1")).thenReturn(participants);
        when(mapper.findInputSnapshots("pcj-1")).thenReturn(snapshots);
        when(mapper.findApprovals(anyString(), anyString())).thenAnswer(ignored -> approvals);
        when(mapper.findParticipantForOwner("pcj-1", 1L)).thenReturn(participants.get(0));
        when(mapper.findParticipantForOwner("pcj-1", 2L)).thenReturn(participants.get(1));
        when(mapper.countEnabledUser(anyLong())).thenReturn(1);
        when(mapper.queueIfFullyApproved(anyString(), anyString())).thenAnswer(ignored -> {
            boolean allApproved = !approvals.isEmpty() && approvals.stream()
                    .allMatch(item -> "APPROVED".equals(item.getDecision()));
            if (!JobStatus.AWAITING_APPROVAL.name().equals(job.getStatus()) || !allApproved) return 0;
            job.setStatus(JobStatus.QUEUED.name());
            return 1;
        });
        when(mapper.decide(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    String party = invocation.getArgument(2);
                    String decision = invocation.getArgument(3);
                    for (ApprovalRecord item : approvals) {
                        if (party.equals(item.getParticipantId()) && "PENDING".equals(item.getDecision())) {
                            item.setDecision(decision);
                            item.setDecisionSignature(invocation.getArgument(5));
                            return 1;
                        }
                    }
                    return 0;
                });
        when(mapper.transition(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String from = invocation.getArgument(2);
            if (!from.equals(job.getStatus())) return 0;
            job.setStatus(invocation.getArgument(3));
            return 1;
        });
        when(mapper.finish(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            job.setStatus(invocation.getArgument(2));
            return 1;
        });
        when(mapper.updateAttemptStatus(anyString(), anyString())).thenReturn(1);
        when(mapper.insertEvent(any())).thenReturn(1);
        when(resolver.sha256(anyString())).thenAnswer(invocation -> repeat('d', 64));
        when(approvalSigner.sign(anyString())).thenReturn(repeat('e', 64));
    }

    @Test
    void createAutoApprovesInitiatorOwnedInputAndLeavesOtherOwnerPending() {
        JobSpec request = new JobSpec();
        request.setTemplateId("psi-2p-v1");
        JobSpec resolved = runtimeSpec();
        AuthenticatedUser initiator = user(1L, "alice");
        TemplateDefinition template = template();
        ProviderCapability capability = capability();
        when(catalog.find("psi-2p-v1")).thenReturn(template);
        when(providers.require(ProviderType.KUSCIA_SECRETFLOW)).thenReturn(provider);
        when(provider.capability()).thenReturn(capability);
        when(resolver.resolve(request, template, initiator)).thenReturn(
                new ResolvedSpec(resolved, snapshots, "{}", repeat('c', 64)));
        when(resolver.sha256(anyString())).thenReturn(repeat('d', 64));
        when(mapper.insertJob(any())).thenAnswer(invocation -> {
            job = invocation.getArgument(0);
            return 1;
        });
        when(mapper.findJob(anyString())).thenAnswer(ignored -> job);

        service.create(request, "request-create", initiator);

        verify(mapper).insertPendingApproval(anyString(), anyString(), eq("A"), eq(1L), eq("alice"),
                anyString(), eq("APPROVED"), anyString());
        verify(mapper).insertPendingApproval(anyString(), anyString(), eq("B"), eq(2L), eq("bob"),
                anyString(), eq("PENDING"), isNull());
        verify(mapper).queueIfFullyApproved(anyString(), anyString());
    }

    @Test
    void wrongUserCannotApprove() {
        when(mapper.findParticipantForOwner("pcj-1", 3L)).thenReturn(null);
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.approve("pcj-1", user(3L, "mallory"), new DecisionRequest()));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(mapper, never()).decide(anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyLong(), anyString());
    }

    @Test
    void finalOwnerApprovalQueuesAndSubmitsExactlyOnce() {
        service.approve("pcj-1", user(2L, "bob"), new DecisionRequest());
        assertEquals(JobStatus.QUEUED.name(), job.getStatus());
        assertEquals(1, submitted.size());
        assertThrows(RegistrationException.class,
                () -> service.approve("pcj-1", user(2L, "bob"), new DecisionRequest()));
        verify(mapper, times(1)).queueIfFullyApproved("pcj-1", "pca-1");
    }

    @Test
    void switchedAdministratorCanApproveAsEffectiveDataOwner() {
        AuthenticatedUser switchedOwner = new AuthenticatedUser(2L, "bob", "bob",
                Collections.singleton("DATA_OWNER"), 2L, "D2", "D2",
                true, 99L, "admin");

        service.approve("pcj-1", switchedOwner, new DecisionRequest());

        assertEquals(JobStatus.QUEUED.name(), job.getStatus());
        assertEquals(1, submitted.size());
        verify(mapper).decide(eq("pcj-1"), eq("pca-1"), eq("B"), eq("APPROVED"),
                isNull(), anyString(), eq(2L), eq("bob"));
    }

    @Test
    void ownerRejectionAbortsWithoutSubmission() {
        service.reject("pcj-1", user(2L, "bob"), reason("not authorized"));
        assertEquals(JobStatus.ABORTED.name(), job.getStatus());
        assertEquals(0, submitted.size());
        verify(mapper).finish("pcj-1", "pca-1", "ABORTED", "PARTICIPANT_REJECTED",
                "participant B rejected this attempt");
    }

    @Test
    void resultCanOnlyBeReadByInitiator() {
        job.setStatus(JobStatus.SUCCEEDED.name());
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.result("pcj-1", user(2L, "bob")));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(providers, never()).require(any());
    }

    @Test
    void administratorCannotApproveOrReadResultEvenWhenIdentityMatches() {
        AuthenticatedUser administrator = userWithRoles(1L, "alice", "ADMIN", "DATA_OWNER");
        RegistrationException approvalError = assertThrows(RegistrationException.class,
                () -> service.approve("pcj-1", administrator, new DecisionRequest()));
        assertEquals("APPROVAL_ROLE_FORBIDDEN", approvalError.getErrorCode());

        job.setStatus(JobStatus.SUCCEEDED.name());
        RegistrationException resultError = assertThrows(RegistrationException.class,
                () -> service.result("pcj-1", administrator));
        assertEquals("RESULT_ACCESS_DENIED", resultError.getErrorCode());
        verify(providers, never()).require(any());
    }

    @Test
    void retryCreatesFreshOwnerApprovalsAndAutoApprovesInitiatorInput() {
        job.setStatus(JobStatus.ABORTED.name());
        TemplateDefinition template = template();
        when(catalog.find("psi-2p-v1")).thenReturn(template);
        when(providers.require(ProviderType.KUSCIA_SECRETFLOW)).thenReturn(provider);
        when(provider.capability()).thenReturn(capability());
        when(mapper.beginRetry(eq("pcj-1"), anyString(), eq(2), eq(1))).thenAnswer(invocation -> {
            job.setCurrentAttemptId(invocation.getArgument(1));
            job.setCurrentAttemptNo(2);
            job.setStatus(JobStatus.AWAITING_APPROVAL.name());
            approvals = new ArrayList<>();
            return 1;
        });
        service.retry("pcj-1", user(1L, "alice"));

        verify(mapper).insertPendingApproval(eq("pcj-1"), anyString(), eq("A"), eq(1L), eq("alice"),
                anyString(), eq("APPROVED"), anyString());
        verify(mapper).insertPendingApproval(eq("pcj-1"), anyString(), eq("B"), eq(2L), eq("bob"),
                anyString(), eq("PENDING"), isNull());
        assertEquals(2, job.getCurrentAttemptNo());
    }

    private JobRecord job(JobStatus status, String attemptId, int attemptNo) throws Exception {
        JobRecord value = new JobRecord();
        value.setJobId("pcj-1");
        value.setRequestId("request-1");
        value.setCurrentAttemptId(attemptId);
        value.setCurrentAttemptNo(attemptNo);
        value.setTemplateId("psi-2p-v1");
        value.setProvider(ProviderType.KUSCIA_SECRETFLOW.name());
        value.setSecurityProfile("SEMI_HONEST");
        value.setStatus(status.name());
        value.setInitiator("alice");
        value.setInitiatorUserId(1L);
        value.setSpecJson(json.writeValueAsString(runtimeSpec()));
        value.setSpecDigest(repeat('c', 64));
        value.setProtocolVersion("psi/rr22");
        value.setImageDigest("sha256:" + repeat('e', 64));
        value.setTimeoutSeconds(1800);
        value.setResultRecipientsJson("[\"A\"]");
        return value;
    }

    private JobSpec runtimeSpec() {
        JobSpec value = new JobSpec();
        value.setTemplateId("psi-2p-v1");
        value.setSecurityProfile("SEMI_HONEST");
        value.setTimeoutSeconds(1800);
        value.setResultRecipients(Collections.singletonList("A"));
        value.setParticipants(participants);
        return value;
    }

    private ParticipantSpec participant(String party, Long ownerId, String owner, Long domainId) {
        ParticipantSpec value = new ParticipantSpec();
        value.setPartyId(party);
        value.setSlotId("A".equals(party) ? "P0" : "P1");
        value.setRole("A".equals(party) ? "RECEIVER" : "PROVIDER");
        value.setOwnerUserId(ownerId);
        value.setOwnerUsername(owner);
        value.setOwnerDomainId(domainId);
        value.setDatasetId("A".equals(party) ? "101" : "202");
        return value;
    }

    private InputSnapshotRecord snapshot(String party, Long datasetId) {
        InputSnapshotRecord value = new InputSnapshotRecord();
        value.setPartyId(party);
        value.setDatasetId(datasetId);
        value.setDatasetVersion("v1");
        value.setDigestValue(repeat('a', 64));
        value.setSchemaDigest(repeat('b', 64));
        value.setFieldsJson("[\"id\"]");
        return value;
    }

    private ApprovalRecord approval(String party, Long userId, String decision) {
        ApprovalRecord value = new ApprovalRecord();
        value.setJobId("pcj-1");
        value.setAttemptId("pca-1");
        value.setParticipantId(party);
        value.setApproverUserId(userId);
        value.setInputSnapshotDigest(repeat('f', 64));
        value.setDecision(decision);
        return value;
    }

    private TemplateDefinition template() {
        TemplateDefinition value = new TemplateDefinition();
        value.setTemplateId("psi-2p-v1");
        value.setProvider(ProviderType.KUSCIA_SECRETFLOW);
        value.setSecurityProfile("SEMI_HONEST");
        value.setAvailable(true);
        value.setProtocolVersion("psi/rr22");
        return value;
    }

    private ProviderCapability capability() {
        ProviderCapability value = new ProviderCapability();
        value.setProvider(ProviderType.KUSCIA_SECRETFLOW);
        value.setStatus(CapabilityStatus.AVAILABLE);
        value.setImageDigest("sha256:" + repeat('e', 64));
        return value;
    }

    private AuthenticatedUser user(Long id, String username) {
        return userWithRoles(id, username, "DATA_OWNER");
    }

    private AuthenticatedUser userWithRoles(Long id, String username, String... roles) {
        return new AuthenticatedUser(id, username, username,
                new LinkedHashSet<>(Arrays.asList(roles)), id, "D" + id, "D" + id);
    }

    private DecisionRequest reason(String reason) {
        DecisionRequest value = new DecisionRequest();
        value.setReason(reason);
        return value;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
