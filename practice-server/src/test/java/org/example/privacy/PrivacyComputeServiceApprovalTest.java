package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.auth.AuthenticatedUser;
import org.example.exception.RegistrationException;
import org.example.privacy.PrivacyComputeModels.ApprovalRecord;
import org.example.privacy.PrivacyComputeModels.ApprovalView;
import org.example.privacy.PrivacyComputeModels.CapabilityStatus;
import org.example.privacy.PrivacyComputeModels.DecisionRequest;
import org.example.privacy.PrivacyComputeModels.InputSnapshotRecord;
import org.example.privacy.PrivacyComputeModels.JobRecord;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.JobStatus;
import org.example.privacy.PrivacyComputeModels.JobView;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Approvals are addressed to the participant's domain: party A is domain 11,
 * party B is domain 22. alice (11) initiates; bob and carol are both domain
 * users of 22; mallory belongs to domain 33, which has no input.
 */
class PrivacyComputeServiceApprovalTest {
    private static final Long DOMAIN_A = 11L;
    private static final Long DOMAIN_B = 22L;

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

        participants = Arrays.asList(participant("A", DOMAIN_A, "domain-a", "上海域（A）"),
                participant("B", DOMAIN_B, "domain-b", "深圳域（B）"));
        snapshots = Arrays.asList(snapshot("A", 101L), snapshot("B", 202L));
        approvals = new ArrayList<>();
        approvals.add(approval("A", DOMAIN_A, "APPROVED", 1L, "alice"));
        approvals.add(approval("B", DOMAIN_B, "PENDING", null, null));
        job = job(JobStatus.AWAITING_APPROVAL, "pca-1", 1);

        when(mapper.findJob("pcj-1")).thenAnswer(ignored -> job);
        when(mapper.findParticipants("pcj-1")).thenReturn(participants);
        when(mapper.findInputSnapshots("pcj-1")).thenReturn(snapshots);
        when(mapper.findApprovals(anyString(), anyString())).thenAnswer(ignored -> approvals);
        when(mapper.findParticipantForDomain("pcj-1", DOMAIN_A)).thenReturn(participants.get(0));
        when(mapper.findParticipantForDomain("pcj-1", DOMAIN_B)).thenReturn(participants.get(1));
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
                            item.setApproverUserId(invocation.getArgument(6));
                            item.setApproverUsername(invocation.getArgument(7));
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
    void createAutoApprovesTheInitiatorsDomainInputAndLeavesTheOtherDomainPending() {
        createAs(user(1L, "alice", DOMAIN_A));

        // Initiator recorded as the approver of its own-domain input.
        verify(mapper).insertPendingApproval(anyString(), anyString(), eq("A"), eq(1L), eq("alice"),
                anyString(), eq("APPROVED"), anyString());
        // The other domain's input waits; nobody has decided yet.
        verify(mapper).insertPendingApproval(anyString(), anyString(), eq("B"), isNull(), isNull(),
                anyString(), eq("PENDING"), isNull());
        verify(mapper).queueIfFullyApproved(anyString(), anyString());
    }

    @Test
    void initiatorAutoApprovalFollowsItsDomainNotItsUserId() {
        // carol belongs to B's domain: B is hers, A waits for domain A.
        createAs(user(4L, "carol", DOMAIN_B));

        verify(mapper).insertPendingApproval(anyString(), anyString(), eq("A"), isNull(), isNull(),
                anyString(), eq("PENDING"), isNull());
        verify(mapper).insertPendingApproval(anyString(), anyString(), eq("B"), eq(4L), eq("carol"),
                anyString(), eq("APPROVED"), anyString());
    }

    @Test
    void userOfADomainWithoutAnInputCannotApprove() {
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.approve("pcj-1", user(3L, "mallory", 33L), new DecisionRequest()));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals("APPROVAL_DOMAIN_MISMATCH", error.getErrorCode());

        RegistrationException domainless = assertThrows(RegistrationException.class,
                () -> service.approve("pcj-1", user(5L, "nomad", null), new DecisionRequest()));
        assertEquals("APPROVAL_DOMAIN_MISMATCH", domainless.getErrorCode());
        verify(mapper, never()).decide(anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyLong(), anyString());
    }

    @Test
    void domainUserApprovalQueuesSubmitsOnceAndRecordsTheDecider() {
        JobView view = service.approve("pcj-1", user(2L, "bob", DOMAIN_B), new DecisionRequest());

        assertEquals(JobStatus.QUEUED.name(), job.getStatus());
        assertEquals(1, submitted.size());
        verify(mapper).decide(eq("pcj-1"), eq("pca-1"), eq("B"), eq("APPROVED"),
                isNull(), anyString(), eq(2L), eq("bob"));
        ApprovalView decided = approvalView(view, "B");
        assertEquals(Long.valueOf(2L), decided.getApproverUserId());
        assertEquals("bob", decided.getApproverUsername());
        assertEquals(DOMAIN_B, decided.getOwnerDomainId());

        assertThrows(RegistrationException.class,
                () -> service.approve("pcj-1", user(2L, "bob", DOMAIN_B), new DecisionRequest()));
        verify(mapper, times(1)).queueIfFullyApproved("pcj-1", "pca-1");
    }

    @Test
    void anySecondUserOfTheParticipantDomainMayDecide() {
        service.approve("pcj-1", user(4L, "carol", DOMAIN_B), new DecisionRequest());

        assertEquals(JobStatus.QUEUED.name(), job.getStatus());
        verify(mapper).decide(eq("pcj-1"), eq("pca-1"), eq("B"), eq("APPROVED"),
                isNull(), anyString(), eq(4L), eq("carol"));
    }

    @Test
    void aDomainDecisionIsFinalForTheOtherUsersOfThatDomain() {
        // Keep A pending so the job stays AWAITING_APPROVAL after B's decision.
        approvals.get(0).setDecision("PENDING");
        service.approve("pcj-1", user(2L, "bob", DOMAIN_B), new DecisionRequest());

        // Same decision again (another user of the domain): idempotent.
        service.approve("pcj-1", user(4L, "carol", DOMAIN_B), new DecisionRequest());
        // A conflicting decision is refused.
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.reject("pcj-1", user(4L, "carol", DOMAIN_B), reason("no")));
        assertEquals("APPROVAL_ALREADY_DECIDED", error.getErrorCode());
        assertEquals("bob", approvals.get(1).getApproverUsername());
    }

    @Test
    void switchedAdministratorCanApproveAsEffectiveDomainUser() {
        AuthenticatedUser switchedOwner = new AuthenticatedUser(2L, "bob", "bob",
                Collections.singleton("DATA_OWNER"), DOMAIN_B, "domain-b", "深圳域（B）",
                true, 99L, "admin");

        service.approve("pcj-1", switchedOwner, new DecisionRequest());

        assertEquals(JobStatus.QUEUED.name(), job.getStatus());
        assertEquals(1, submitted.size());
        verify(mapper).decide(eq("pcj-1"), eq("pca-1"), eq("B"), eq("APPROVED"),
                isNull(), anyString(), eq(2L), eq("bob"));
    }

    @Test
    void domainUserRejectionAbortsWithoutSubmission() {
        service.reject("pcj-1", user(4L, "carol", DOMAIN_B), reason("not authorized"));
        assertEquals(JobStatus.ABORTED.name(), job.getStatus());
        assertEquals(0, submitted.size());
        verify(mapper).finish("pcj-1", "pca-1", "ABORTED", "PARTICIPANT_REJECTED",
                "participant B rejected this attempt");
    }

    @Test
    void disabledDomainUserCannotDecide() {
        when(mapper.countEnabledUser(4L)).thenReturn(0);
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.approve("pcj-1", user(4L, "carol", DOMAIN_B), new DecisionRequest()));
        assertEquals("APPROVAL_USER_DISABLED", error.getErrorCode());
    }

    @Test
    void pendingApprovalsAreTheJobsWaitingForTheCallersDomain() {
        when(mapper.listPendingApprovals(DOMAIN_B, 50)).thenReturn(Collections.singletonList(job));

        List<JobView> bob = service.pendingApprovals(null, user(2L, "bob", DOMAIN_B));
        List<JobView> carol = service.pendingApprovals(null, user(4L, "carol", DOMAIN_B));

        assertEquals(1, bob.size());
        assertEquals("pcj-1", carol.get(0).getJobId());
        verify(mapper, times(2)).listPendingApprovals(DOMAIN_B, 50);
        // Before a decision nobody is named as the approver.
        ApprovalView pending = approvalView(carol.get(0), "B");
        assertEquals("PENDING", pending.getDecision());
        assertNull(pending.getApproverUserId());
        assertNull(pending.getApproverUsername());
        assertEquals(DOMAIN_B, pending.getOwnerDomainId());
        assertEquals("domain-b", pending.getOwnerDomainCode());
        assertEquals("深圳域（B）", pending.getOwnerDomainName());
    }

    @Test
    void pendingApprovalsOfADomainlessOrNonDomainUser() {
        assertTrue(service.pendingApprovals(null, user(5L, "nomad", null)).isEmpty());
        verify(mapper, never()).listPendingApprovals(any(), anyInt());

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.pendingApprovals(null, userWithRoles(6L, "auditor", DOMAIN_B, "AUDITOR")));
        assertEquals("DATA_OWNER_REQUIRED", error.getErrorCode());
    }

    @Test
    void jobVisibilityFollowsParticipantDomains() {
        assertEquals("pcj-1", service.getAuthorized("pcj-1", user(4L, "carol", DOMAIN_B)).getJobId());

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.getAuthorized("pcj-1", user(3L, "mallory", 33L)));
        assertEquals("PRIVACY_JOB_ACCESS_DENIED", error.getErrorCode());

        service.listAuthorized(null, null, user(4L, "carol", DOMAIN_B));
        verify(mapper).listJobsForParticipant(4L, DOMAIN_B, null, 50);
    }

    @Test
    void resultCanOnlyBeReadByInitiator() {
        job.setStatus(JobStatus.SUCCEEDED.name());
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.result("pcj-1", user(2L, "bob", DOMAIN_B)));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(providers, never()).require(any());
    }

    @Test
    void administratorCannotApproveOrReadResultEvenWhenIdentityMatches() {
        AuthenticatedUser administrator = userWithRoles(1L, "alice", DOMAIN_A, "ADMIN", "DATA_OWNER");
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
    void retryCreatesFreshDomainApprovalsAndAutoApprovesInitiatorDomainInput() {
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
        service.retry("pcj-1", user(1L, "alice", DOMAIN_A));

        verify(mapper).insertPendingApproval(eq("pcj-1"), anyString(), eq("A"), eq(1L), eq("alice"),
                anyString(), eq("APPROVED"), anyString());
        verify(mapper).insertPendingApproval(eq("pcj-1"), anyString(), eq("B"), isNull(), isNull(),
                anyString(), eq("PENDING"), isNull());
        assertEquals(2, job.getCurrentAttemptNo());
    }

    @Test
    void storedSpecsWithTheRetiredHolderFieldsStillLoad() throws Exception {
        // Jobs frozen before the domain model carry ownerUserId/ownerUsername per participant.
        job.setSpecJson("{\"templateId\":\"psi-2p-v1\",\"securityProfile\":\"SEMI_HONEST\","
                + "\"timeoutSeconds\":1800,\"resultRecipients\":[\"A\"],\"participants\":["
                + "{\"slotId\":\"P0\",\"partyId\":\"A\",\"role\":\"RECEIVER\",\"ownerUserId\":1,"
                + "\"ownerUsername\":\"alice\",\"ownerDomainId\":11,\"ownerDomainCode\":\"domain-a\","
                + "\"datasetId\":\"101\",\"fields\":[\"id\"]}]}");

        JobView view = service.getAuthorized("pcj-1", user(1L, "alice", DOMAIN_A));

        assertEquals(DOMAIN_A, view.getParticipants().get(0).getOwnerDomainId());
        assertEquals("domain-a", view.getParticipants().get(0).getOwnerDomainCode());
        String reserialized = json.writeValueAsString(view.getParticipants().get(0));
        assertTrue(!reserialized.contains("ownerUserId") && !reserialized.contains("ownerUsername"),
                reserialized);
    }

    private void createAs(AuthenticatedUser initiator) {
        JobSpec request = new JobSpec();
        request.setTemplateId("psi-2p-v1");
        JobSpec resolved = runtimeSpec();
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
    }

    private ApprovalView approvalView(JobView view, String participantId) {
        return view.getApprovals().stream().filter(item -> participantId.equals(item.getParticipantId()))
                .findFirst().orElseThrow(AssertionError::new);
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

    private ParticipantSpec participant(String party, Long domainId, String domainCode, String domainName) {
        ParticipantSpec value = new ParticipantSpec();
        value.setPartyId(party);
        value.setSlotId("A".equals(party) ? "P0" : "P1");
        value.setRole("A".equals(party) ? "RECEIVER" : "PROVIDER");
        value.setOwnerDomainId(domainId);
        value.setOwnerDomainCode(domainCode);
        value.setOwnerDomainName(domainName);
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

    private ApprovalRecord approval(String party, Long domainId, String decision,
                                    Long approverUserId, String approverUsername) {
        ApprovalRecord value = new ApprovalRecord();
        value.setJobId("pcj-1");
        value.setAttemptId("pca-1");
        value.setParticipantId(party);
        value.setOwnerDomainId(domainId);
        value.setOwnerDomainCode(DOMAIN_A.equals(domainId) ? "domain-a" : "domain-b");
        value.setApproverUserId(approverUserId);
        value.setApproverUsername(approverUsername);
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

    private AuthenticatedUser user(Long id, String username, Long domainId) {
        return userWithRoles(id, username, domainId, "DATA_OWNER");
    }

    private AuthenticatedUser userWithRoles(Long id, String username, Long domainId, String... roles) {
        return new AuthenticatedUser(id, username, username,
                new LinkedHashSet<>(Arrays.asList(roles)), domainId,
                domainId == null ? null : "domain-" + domainId, domainId == null ? null : "D" + domainId);
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
