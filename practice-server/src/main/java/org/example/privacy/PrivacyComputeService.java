package org.example.privacy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.RegistrationException;
import org.example.privacy.PrivacyComputeModels.ActionRequest;
import org.example.privacy.PrivacyComputeModels.ApprovalRecord;
import org.example.privacy.PrivacyComputeModels.ApprovalView;
import org.example.privacy.PrivacyComputeModels.CapabilityStatus;
import org.example.privacy.PrivacyComputeModels.DecisionRequest;
import org.example.privacy.PrivacyComputeModels.EventRecord;
import org.example.privacy.PrivacyComputeModels.EvidenceRecord;
import org.example.privacy.PrivacyComputeModels.InputSnapshotRecord;
import org.example.privacy.PrivacyComputeModels.JobRecord;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.JobStatus;
import org.example.privacy.PrivacyComputeModels.JobView;
import org.example.privacy.PrivacyComputeModels.PreflightResult;
import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ProviderRunStatus;
import org.example.privacy.PrivacyComputeModels.ProviderSubmission;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.example.privacy.PrivacyComputeModels.ResultRecord;
import org.example.privacy.PrivacyComputeModels.ResultView;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.example.privacy.PrivacyJobSpecResolver.ResolvedSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Service
public class PrivacyComputeService {
    private static final int MAX_ATTEMPTS = 3;
    private static final long POLL_INTERVAL_MILLIS = 1000L;

    private final PrivacyComputeMapper mapper;
    private final PrivacyTemplateCatalog catalog;
    private final PrivacyProviderRegistry providers;
    private final PrivacyJobSpecResolver resolver;
    private final PrivacyPartyAuthenticator authenticator;
    private final PrivacyInputStagingService staging;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Map<ProviderType, Semaphore> providerLocks = new EnumMap<>(ProviderType.class);

    public PrivacyComputeService(PrivacyComputeMapper mapper,
                                 PrivacyTemplateCatalog catalog,
                                 PrivacyProviderRegistry providers,
                                 PrivacyJobSpecResolver resolver,
                                 PrivacyPartyAuthenticator authenticator,
                                 PrivacyInputStagingService staging,
                                 ObjectMapper objectMapper,
                                 @Qualifier("privacyComputeExecutor") Executor executor) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.providers = providers;
        this.resolver = resolver;
        this.authenticator = authenticator;
        this.staging = staging;
        this.objectMapper = objectMapper;
        this.executor = executor;
        for (ProviderType type : ProviderType.values()) providerLocks.put(type, new Semaphore(1, true));
    }

    public List<ProviderCapability> capabilities() {
        return providers.capabilities();
    }

    public List<TemplateDefinition> templates() {
        return catalog.list();
    }

    public PreflightResult preflight(JobSpec request, String principal) {
        PreflightResult result = new PreflightResult();
        TemplateDefinition template = catalog.find(request == null ? null : request.getTemplateId());
        result.setTemplate(template);
        if (template == null) {
            result.getErrors().add("unknown privacy template");
            return result;
        }
        if (!template.isAvailable()) {
            result.getErrors().add("provider unavailable: " + template.getUnavailableReason());
            return result;
        }
        try {
            ResolvedSpec resolved = resolver.resolve(request, template, principal);
            providers.require(template.getProvider()).validate(resolved.getSpec(), template);
            staging.validateFixedNodeReplicas(resolved.getSpec());
            result.setSpecDigest(resolved.getSpecDigest());
            result.setValid(true);
            result.getWarnings().add(template.getLeakageDisclosure());
            if (template.isExperimental()) {
                result.getWarnings().add("This template uses an experimental upstream component.");
            }
        } catch (RuntimeException ex) {
            result.getErrors().add(safeFailure(ex));
        }
        return result;
    }

    @Transactional
    public JobView create(JobSpec request, String requestId, String principal) {
        if (blank(requestId)) throw RegistrationException.invalid("IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key is required");
        TemplateDefinition template = catalog.find(request == null ? null : request.getTemplateId());
        if (template == null) throw RegistrationException.invalid("PRIVACY_TEMPLATE_UNKNOWN",
                "unknown privacy template");
        if (!template.isAvailable()) throw RegistrationException.conflict("PRIVACY_PROVIDER_UNAVAILABLE",
                "provider unavailable: " + template.getUnavailableReason());

        ResolvedSpec resolved = resolver.resolve(request, template, principal);
        PrivacyComputeProvider provider = providers.require(template.getProvider());
        provider.validate(resolved.getSpec(), template);
        staging.validateFixedNodeReplicas(resolved.getSpec());
        ProviderCapability capability = provider.capability();
        if (capability.getStatus() != CapabilityStatus.AVAILABLE || blank(capability.getImageDigest())) {
            throw RegistrationException.conflict("PRIVACY_PROVIDER_UNAVAILABLE",
                    "provider is not healthy with an immutable image digest");
        }

        String jobId = "pcj-" + UUID.randomUUID();
        String attemptId = "pca-" + UUID.randomUUID();
        JobRecord job = new JobRecord();
        job.setJobId(jobId);
        job.setRequestId(requestId.trim());
        job.setCurrentAttemptId(attemptId);
        job.setCurrentAttemptNo(1);
        job.setTemplateId(template.getTemplateId());
        job.setProvider(template.getProvider().name());
        job.setSecurityProfile(template.getSecurityProfile());
        job.setStatus(JobStatus.AWAITING_APPROVAL.name());
        job.setInitiator(principal.trim().toUpperCase());
        job.setResultRecipientsJson(json(resolved.getSpec().getResultRecipients()));
        job.setTimeoutSeconds(resolved.getSpec().getTimeoutSeconds());
        job.setEnginePolicyJson(json(resolved.getSpec().getEnginePolicy()));
        job.setSpecJson(resolved.getSpecJson());
        job.setSpecDigest(resolved.getSpecDigest());
        job.setProtocolVersion(template.getProtocolVersion());
        job.setImageDigest(capability.getImageDigest());
        mapper.insertJob(job);
        mapper.insertAttempt(attemptId, jobId, 1, UUID.randomUUID().toString());
        for (PrivacyComputeModels.ParticipantSpec participant : resolved.getSpec().getParticipants()) {
            mapper.insertParticipant(jobId, participant);
            mapper.insertPendingApproval(jobId, attemptId, participant.getPartyId());
        }
        for (InputSnapshotRecord snapshot : resolved.getSnapshots()) {
            snapshot.setJobId(jobId);
            mapper.insertInputSnapshot(snapshot);
        }
        event(jobId, attemptId, null, "CREATED", "PENDING", "JOB_AWAITING_APPROVAL",
                (long) resolved.getSpecJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                resolved.getSpecDigest(), "all participants must approve this attempt");
        return requireJob(jobId);
    }

    public JobView get(String jobId) {
        return requireJob(jobId);
    }

    public JobView getAuthorized(String jobId, String principal) {
        JobRecord job = requireRecord(jobId);
        requireParticipantOrInitiator(job, principal);
        return toView(job);
    }

    public List<JobView> list(String status, Integer requestedLimit) {
        String normalized = null;
        if (!blank(status)) {
            try {
                normalized = JobStatus.valueOf(status.trim().toUpperCase()).name();
            } catch (IllegalArgumentException ex) {
                throw RegistrationException.invalid("PRIVACY_STATUS_INVALID", "unknown privacy job status");
            }
        }
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 200) {
            throw RegistrationException.invalid("PRIVACY_LIMIT_INVALID", "limit must be between 1 and 200");
        }
        List<JobView> result = new ArrayList<>();
        for (JobRecord row : mapper.listJobs(normalized, limit)) result.add(toView(row));
        return result;
    }

    public List<JobView> listAuthorized(String status, Integer requestedLimit, String principal) {
        String actor = normalizePrincipal(principal);
        String normalized = normalizeStatus(status);
        int limit = normalizeLimit(requestedLimit);
        List<JobView> result = new ArrayList<>();
        for (JobRecord row : mapper.listJobsForParticipant(actor, normalized, limit)) result.add(toView(row));
        return result;
    }

    public List<EventRecord> events(String jobId) {
        requireRecord(jobId);
        return mapper.findEvents(jobId);
    }

    public List<EventRecord> eventsAuthorized(String jobId, String principal) {
        JobRecord job = requireRecord(jobId);
        requireParticipantOrInitiator(job, principal);
        return mapper.findEvents(jobId);
    }

    @Transactional
    public JobView approve(String jobId, String principal, DecisionRequest request) {
        return decide(jobId, principal, request, true);
    }

    @Transactional
    public JobView reject(String jobId, String principal, DecisionRequest request) {
        return decide(jobId, principal, request, false);
    }

    private JobView decide(String jobId, String principal, DecisionRequest request, boolean approve) {
        JobRecord job = requireRecord(jobId);
        requireAwaitingApproval(job);
        String participant = requireParticipantPrincipal(job, principal, request == null ? null : request.getParticipantId());
        String reason = sanitizeReason(request == null ? null : request.getReason());
        String decision = approve ? "APPROVED" : "REJECTED";
        String canonicalDecision = jobId + "\n" + job.getCurrentAttemptId() + "\n" + participant
                + "\n" + decision + "\n" + (reason == null ? "" : reason) + "\n" + job.getSpecDigest();
        String signature = authenticator.signApproval(participant, canonicalDecision);
        int changed = mapper.decide(jobId, job.getCurrentAttemptId(), participant, decision, reason, signature);
        if (changed == 0) {
            for (ApprovalRecord existing : mapper.findApprovals(jobId, job.getCurrentAttemptId())) {
                if (participant.equals(existing.getParticipantId()) && decision.equals(existing.getDecision())) {
                    return requireJob(jobId);
                }
            }
            throw RegistrationException.conflict("APPROVAL_ALREADY_DECIDED",
                    "participant already made a different decision for this attempt");
        }
        event(jobId, job.getCurrentAttemptId(), participant, "APPROVAL", decision,
                approve ? "PARTICIPANT_APPROVED" : "PARTICIPANT_REJECTED", null,
                resolver.sha256(canonicalDecision), reason);
        if (!approve) {
            finish(job, JobStatus.ABORTED, "PARTICIPANT_REJECTED",
                    "participant " + participant + " rejected this attempt");
            return requireJob(jobId);
        }

        int total = mapper.countApprovals(jobId, job.getCurrentAttemptId());
        int approved = mapper.countApproved(jobId, job.getCurrentAttemptId());
        if (total > 0 && approved == total
                && mapper.transition(jobId, job.getCurrentAttemptId(), JobStatus.AWAITING_APPROVAL.name(),
                JobStatus.QUEUED.name()) == 1) {
            mapper.updateAttemptStatus(job.getCurrentAttemptId(), JobStatus.QUEUED.name());
            event(jobId, job.getCurrentAttemptId(), null, "QUEUE", "QUEUED", "ALL_PARTICIPANTS_APPROVED",
                    null, null, null);
            afterCommit(() -> submit(jobId, job.getCurrentAttemptId()));
        }
        return requireJob(jobId);
    }

    @Transactional
    public JobView cancel(String jobId, String principal, ActionRequest request) {
        JobRecord job = requireRecord(jobId);
        requireParticipantOrInitiator(job, principal);
        JobStatus current = JobStatus.valueOf(job.getStatus());
        if (current.terminal()) throw RegistrationException.conflict("PRIVACY_JOB_TERMINAL",
                "terminal job cannot be cancelled");
        String reason = sanitizeReason(request == null ? null : request.getReason());
        if (mapper.finish(jobId, job.getCurrentAttemptId(), JobStatus.CANCELLED.name(),
                "CANCELLED_BY_PARTICIPANT", reason) != 1) {
            throw RegistrationException.conflict("PRIVACY_JOB_STATE_CHANGED", "job state changed; refresh and retry");
        }
        mapper.finishAttempt(job.getCurrentAttemptId(), JobStatus.CANCELLED.name(),
                "CANCELLED_BY_PARTICIPANT", reason);
        event(jobId, job.getCurrentAttemptId(), principal.trim().toUpperCase(), "CANCEL", "CANCELLED",
                "JOB_CANCELLED", null, null, reason);
        if (!blank(job.getExternalJobId())) {
            afterCommit(() -> safeCancel(job, reason));
        }
        return requireJob(jobId);
    }

    @Transactional
    public JobView retry(String jobId, String principal) {
        JobRecord job = requireRecord(jobId);
        if (!job.getInitiator().equals(normalizePrincipal(principal))) {
            throw new RegistrationException(HttpStatus.FORBIDDEN, "RETRY_FORBIDDEN",
                    "only the initiator can retry a privacy job");
        }
        JobStatus status = JobStatus.valueOf(job.getStatus());
        if (!(status == JobStatus.FAILED || status == JobStatus.ABORTED || status == JobStatus.CANCELLED)) {
            throw RegistrationException.conflict("PRIVACY_RETRY_STATE_INVALID",
                    "only failed, aborted, or cancelled jobs can be retried");
        }
        int current = job.getCurrentAttemptNo();
        if (current >= MAX_ATTEMPTS) throw RegistrationException.conflict("PRIVACY_RETRY_LIMIT",
                "privacy job is limited to three attempts");

        TemplateDefinition template = catalog.find(job.getTemplateId());
        if (template == null || !template.isAvailable()) {
            throw RegistrationException.conflict("PRIVACY_PROVIDER_UNAVAILABLE",
                    "provider is unavailable; retry cannot downgrade to another engine");
        }
        ProviderCapability capability = providers.require(template.getProvider()).capability();
        if (capability.getStatus() != CapabilityStatus.AVAILABLE
                || !job.getImageDigest().equals(capability.getImageDigest())) {
            throw RegistrationException.conflict("PRIVACY_ENGINE_CHANGED",
                    "retry requires the exact engine image digest frozen by the original job");
        }

        int next = current + 1;
        String attemptId = "pca-" + UUID.randomUUID();
        mapper.insertAttempt(attemptId, jobId, next, UUID.randomUUID().toString());
        if (mapper.beginRetry(jobId, attemptId, next, current) != 1) {
            throw RegistrationException.conflict("PRIVACY_JOB_STATE_CHANGED", "job state changed; refresh and retry");
        }
        for (String participant : mapper.findParticipantIds(jobId)) {
            mapper.insertPendingApproval(jobId, attemptId, participant);
        }
        event(jobId, attemptId, null, "RETRY", "PENDING", "FRESH_ATTEMPT_CREATED",
                null, resolver.sha256(attemptId), "new approvals and protocol randomness are required");
        return requireJob(jobId);
    }

    public ResultView result(String jobId, String principal) {
        JobRecord job = requireRecord(jobId);
        String actor = normalizePrincipal(principal);
        List<String> recipients = readList(job.getResultRecipientsJson());
        if (!recipients.contains(actor)) {
            throw new RegistrationException(HttpStatus.FORBIDDEN, "RESULT_ACCESS_DENIED",
                    "principal is not an authorized result recipient");
        }
        if (!JobStatus.SUCCEEDED.name().equals(job.getStatus())) {
            throw RegistrationException.conflict("PRIVACY_RESULT_NOT_READY", "privacy result is not ready");
        }
        ResultRecord record = mapper.findResult(jobId, job.getCurrentAttemptId());
        if (record == null) throw RegistrationException.conflict("PRIVACY_RESULT_METADATA_MISSING",
                "result metadata is unavailable");
        Object value;
        try {
            value = providers.require(ProviderType.valueOf(job.getProvider()))
                    .result(job.getExternalJobId(), record.getResultReference());
        } catch (RuntimeException ex) {
            throw RegistrationException.conflict("PRIVACY_RESULT_FETCH_FAILED", safeFailure(ex));
        }
        String measured = resolver.sha256(resolver.canonicalJson(value));
        if (!record.getResultDigest().equals(measured)) {
            throw RegistrationException.conflict("PRIVACY_RESULT_DIGEST_MISMATCH",
                    "runtime result does not match the frozen result digest");
        }
        ResultView result = new ResultView();
        result.setJobId(jobId);
        result.setAttemptId(job.getCurrentAttemptId());
        result.setStatus(job.getStatus());
        result.setResultReference(record.getResultReference());
        result.setResultDigest(record.getResultDigest());
        result.setMediaType(record.getMediaType());
        result.setResult(value);
        return result;
    }

    public Map<String, Object> evidence(String jobId, String principal) {
        JobRecord job = requireRecord(jobId);
        requireParticipantOrInitiator(job, principal);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", toView(job));
        result.put("inputSnapshots", mapper.findInputSnapshots(jobId));
        result.put("approvals", mapper.findApprovals(jobId, job.getCurrentAttemptId()));
        result.put("events", mapper.findEvents(jobId));
        ResultRecord resultRecord = mapper.findResult(jobId, job.getCurrentAttemptId());
        result.put("resultMetadata", resultRecord);
        EvidenceRecord persisted = mapper.findEvidence(jobId, job.getCurrentAttemptId());
        if (persisted != null) {
            result.put("providerEvidence", readMap(persisted.getEvidenceJson()));
            result.put("providerEvidenceDigest", persisted.getEvidenceDigest());
            result.put("providerEvidenceReference", persisted.getEvidenceReference());
        }
        result.put("evidenceDigest", resolver.sha256(resolver.canonicalJson(result)));
        return result;
    }

    /** Restarts durable polling after an application restart. Provider start is idempotent by attemptId. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeNonTerminalJobs() {
        for (String status : new String[]{"QUEUED", "PREPARING", "RUNNING", "FINALIZING"}) {
            for (JobRecord job : mapper.listJobs(status, 200)) submit(job.getJobId(), job.getCurrentAttemptId());
        }
    }

    void executeAttempt(String jobId, String attemptId) {
        JobRecord job = mapper.findJob(jobId);
        if (job == null || !attemptId.equals(job.getCurrentAttemptId())) return;
        ProviderType providerType = ProviderType.valueOf(job.getProvider());
        Semaphore lock = providerLocks.get(providerType);
        lock.acquireUninterruptibly();
        try {
            executeAttemptLocked(jobId, attemptId, job);
        } finally {
            lock.release();
        }
    }

    private void executeAttemptLocked(String jobId, String attemptId, JobRecord initialJob) {
        JobRecord job = initialJob;
        try {
            JobStatus state = JobStatus.valueOf(job.getStatus());
            if (state == JobStatus.QUEUED) {
                if (mapper.transition(jobId, attemptId, JobStatus.QUEUED.name(), JobStatus.PREPARING.name()) != 1) return;
                mapper.updateAttemptStatus(attemptId, JobStatus.PREPARING.name());
                event(jobId, attemptId, null, "PREPARE", "RUNNING", "PROVIDER_PREPARATION_STARTED",
                        null, null, null);
                job = requireRecord(jobId);
                state = JobStatus.PREPARING;
            }

            PrivacyComputeProvider provider = providers.require(ProviderType.valueOf(job.getProvider()));
            TemplateDefinition template = catalog.find(job.getTemplateId());
            JobSpec spec = readSpec(job.getSpecJson());
            String externalId = job.getExternalJobId();
            ProviderSubmission current = null;
            if (state == JobStatus.PREPARING && blank(externalId)) {
                ProviderCapability capability = provider.capability();
                if (capability.getStatus() != CapabilityStatus.AVAILABLE
                        || !job.getImageDigest().equals(capability.getImageDigest())) {
                    throw new ProviderFailure("ENGINE_UNAVAILABLE_OR_CHANGED",
                            "provider is unavailable or no longer reports the frozen image digest");
                }
                provider.validate(spec, template);
                ProviderExecutionRequest execution = new ProviderExecutionRequest();
                execution.setJobId(jobId);
                execution.setAttemptId(attemptId);
                execution.setTemplateId(job.getTemplateId());
                execution.setProtocolVersion(job.getProtocolVersion());
                execution.setImageDigest(job.getImageDigest());
                execution.setSpecDigest(digestWire(job.getSpecDigest()));
                execution.setSecurityProfile(job.getSecurityProfile());
                execution.setParticipants(spec.getParticipants());
                execution.setResultRecipients(spec.getResultRecipients());
                execution.setTimeoutSeconds(spec.getTimeoutSeconds());
                execution.setEnginePolicy(spec.getEnginePolicy());
                // Tokens are minted only now and live only in this dispatch request.
                execution.setStaging(staging.prepare(jobId, attemptId, spec));
                current = provider.start(execution);
                if (current == null || blank(current.getExternalJobId())) {
                    throw new ProviderFailure("ENGINE_START_INVALID", "provider returned no external job id");
                }
                externalId = current.getExternalJobId();
                mapper.setExternalJobId(jobId, attemptId, externalId);
                mapper.setAttemptExternalJobId(attemptId, externalId);
            }
            if (state == JobStatus.PREPARING) {
                if (mapper.transition(jobId, attemptId, JobStatus.PREPARING.name(), JobStatus.RUNNING.name()) != 1) return;
                mapper.updateAttemptStatus(attemptId, JobStatus.RUNNING.name());
                event(jobId, attemptId, null, "EXECUTE", "RUNNING", "PROVIDER_JOB_STARTED",
                        null, resolver.sha256(externalId), null);
                job = requireRecord(jobId);
                state = JobStatus.RUNNING;
            }

            if (state == JobStatus.RUNNING) {
                current = poll(provider, job, externalId);
                if (current.getStatus() == ProviderRunStatus.FAILED) {
                    persistTerminalEvidence(provider, job, attemptId, externalId);
                    finish(job, JobStatus.FAILED, emptyDefault(current.getFailureCode(), "ENGINE_FAILED"),
                            emptyDefault(current.getFailureReason(), "privacy runtime failed"));
                    return;
                }
                if (current.getStatus() == ProviderRunStatus.ABORTED) {
                    persistTerminalEvidence(provider, job, attemptId, externalId);
                    finish(job, JobStatus.ABORTED, emptyDefault(current.getFailureCode(), "ENGINE_ABORTED"),
                            emptyDefault(current.getFailureReason(), "privacy runtime aborted the protocol"));
                    return;
                }
                if (current.getStatus() == ProviderRunStatus.CANCELLED) {
                    persistTerminalEvidence(provider, job, attemptId, externalId);
                    finish(job, JobStatus.CANCELLED, "ENGINE_CANCELLED", "privacy runtime cancelled the job");
                    return;
                }
                if (current.getStatus() != ProviderRunStatus.SUCCEEDED) {
                    throw new ProviderFailure("ENGINE_STATUS_INVALID", "provider returned an invalid terminal status");
                }
                if (mapper.transition(jobId, attemptId, JobStatus.RUNNING.name(), JobStatus.FINALIZING.name()) != 1) return;
                mapper.updateAttemptStatus(attemptId, JobStatus.FINALIZING.name());
                event(jobId, attemptId, null, "FINALIZE", "RUNNING", "RESULT_METADATA_VALIDATION_STARTED",
                        null, null, null);
                state = JobStatus.FINALIZING;
            }

            if (state == JobStatus.FINALIZING) {
                if (current == null || current.getStatus() != ProviderRunStatus.SUCCEEDED) {
                    current = poll(provider, job, externalId);
                }
                if (current.getStatus() != ProviderRunStatus.SUCCEEDED) {
                    throw new ProviderFailure("ENGINE_FINAL_STATE_CHANGED",
                            "provider no longer reports a successful terminal state");
                }
                ResultRecord result = validatedResult(jobId, attemptId, current);
                if (mapper.findResult(jobId, attemptId) == null) mapper.insertResult(result);
                if (mapper.findEvidence(jobId, attemptId) == null) {
                    EvidenceRecord evidence = durableProviderEvidenceWithRetry(
                            provider, job, attemptId, externalId);
                    mapper.insertEvidence(evidence);
                }
                if (mapper.transition(jobId, attemptId, JobStatus.FINALIZING.name(), JobStatus.SUCCEEDED.name()) == 1) {
                    mapper.finishAttempt(attemptId, JobStatus.SUCCEEDED.name(), null, null);
                    event(jobId, attemptId, null, "COMPLETE", "SUCCEEDED", "PRIVACY_JOB_SUCCEEDED",
                            null, result.getResultDigest(), null);
                }
            }
        } catch (ProviderFailure ex) {
            finish(requireRecord(jobId), JobStatus.FAILED, ex.code, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finish(requireRecord(jobId), JobStatus.FAILED, "WORKER_INTERRUPTED", "provider polling was interrupted");
        } catch (RuntimeException ex) {
            finish(requireRecord(jobId), JobStatus.FAILED, "CONTROL_PLANE_ERROR", safeFailure(ex));
        }
    }

    private ProviderSubmission poll(PrivacyComputeProvider provider, JobRecord initial, String externalId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(initial.getTimeoutSeconds()).toNanos();
        ProviderSubmission current = null;
        while (System.nanoTime() < deadline) {
            JobRecord refreshed = requireRecord(initial.getJobId());
            if (!initial.getCurrentAttemptId().equals(refreshed.getCurrentAttemptId())) {
                throw new ProviderFailure("ATTEMPT_REPLACED", "attempt is no longer current");
            }
            JobStatus status = JobStatus.valueOf(refreshed.getStatus());
            if (status == JobStatus.CANCELLED || status == JobStatus.ABORTED) {
                provider.cancel(externalId, "control-plane state changed to " + status);
                throw new ProviderFailure("ATTEMPT_CANCELLED", "attempt was cancelled");
            }
            try {
                current = provider.status(externalId);
            } catch (ProviderFailure ex) {
                throw ex;
            } catch (RuntimeException ex) {
                // A transient gateway/network read must not turn a live or
                // already-completed protocol into a permanent control-plane
                // failure. Keep polling until the frozen job deadline.
                Thread.sleep(POLL_INTERVAL_MILLIS);
                continue;
            }
            if (current == null || current.getStatus() == null) {
                throw new ProviderFailure("ENGINE_STATUS_INVALID", "provider returned no status");
            }
            if (current.getStatus() == ProviderRunStatus.SUCCEEDED
                    || current.getStatus() == ProviderRunStatus.FAILED
                    || current.getStatus() == ProviderRunStatus.ABORTED
                    || current.getStatus() == ProviderRunStatus.CANCELLED) return current;
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        provider.cancel(externalId, "control-plane timeout");
        throw new ProviderFailure("ENGINE_TIMEOUT", "privacy runtime exceeded timeoutSeconds");
    }

    private EvidenceRecord durableProviderEvidenceWithRetry(
            PrivacyComputeProvider provider, JobRecord job, String attemptId, String externalId)
            throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return durableProviderEvidence(provider, job, attemptId, externalId);
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt < 2) Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        }
        throw last == null
                ? new ProviderFailure("PROVIDER_EVIDENCE_MISSING", "provider evidence is unavailable")
                : last;
    }

    private ResultRecord validatedResult(String jobId, String attemptId, ProviderSubmission value) {
        if (value == null || blank(value.getResultReference()) || blank(value.getResultDigest())
                || blank(value.getResultMediaType())) {
            throw new ProviderFailure("RESULT_METADATA_INVALID",
                    "provider omitted result reference, digest, or media type");
        }
        String digest = value.getResultDigest().trim().toLowerCase();
        if (digest.startsWith("sha256:")) digest = digest.substring(7);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new ProviderFailure("RESULT_DIGEST_INVALID", "provider result digest is not SHA-256");
        }
        ResultRecord result = new ResultRecord();
        result.setJobId(jobId);
        result.setAttemptId(attemptId);
        result.setResultReference(value.getResultReference());
        result.setResultDigest(digest);
        result.setMediaType(value.getResultMediaType());
        return result;
    }

    private EvidenceRecord durableProviderEvidence(PrivacyComputeProvider provider, JobRecord job,
                                                     String attemptId, String externalId) {
        Map<String, Object> raw = provider.evidence(externalId);
        Map<String, Object> sanitized = sanitizeProviderEvidence(raw);
        if (sanitized.isEmpty()) {
            throw new ProviderFailure("PROVIDER_EVIDENCE_MISSING",
                    "provider returned no safe evidence metadata");
        }
        String canonical = resolver.canonicalJson(sanitized);
        EvidenceRecord result = new EvidenceRecord();
        result.setJobId(job.getJobId());
        result.setAttemptId(attemptId);
        Object reference = sanitized.get("evidenceReference");
        result.setEvidenceReference(reference == null ? null : String.valueOf(reference));
        result.setEvidenceJson(canonical);
        result.setEvidenceDigest(resolver.sha256(canonical));
        return result;
    }

    private void persistTerminalEvidence(PrivacyComputeProvider provider, JobRecord job,
                                         String attemptId, String externalId) {
        if (blank(externalId) || mapper.findEvidence(job.getJobId(), attemptId) != null) return;
        try {
            mapper.insertEvidence(durableProviderEvidence(provider, job, attemptId, externalId));
        } catch (RuntimeException ex) {
            event(job.getJobId(), attemptId, null, "EVIDENCE", "UNAVAILABLE",
                    "PROVIDER_EVIDENCE_UNAVAILABLE", null, null, safeFailure(ex));
        }
    }

    /** Allow-list sanitizer: secrets, scoped read tokens and raw protocol/input payloads cannot be persisted. */
    Map<String, Object> sanitizeProviderEvidence(Map<String, Object> raw) {
        if (raw == null) return Collections.emptyMap();
        Set<String> allowed = new HashSet<>(java.util.Arrays.asList(
                "contractversion", "externaljobid", "provider", "providerid", "engine", "version",
                "engineversion", "sourcerevision", "imagedigest", "protocolversion", "runtimejobid",
                "templateid", "specdigest", "requestdigest", "resultrecipients",
                "status", "phase", "code", "startedat", "completedat", "durationms",
                "finishedat", "engineexitcode", "failurecode", "enginelogdigest", "enginelogbytes",
                "engineevidence", "plaintextfallback", "parties", "allpartiesrequired",
                "unavailable", "reason", "protocol", "protocolexecutable", "program",
                "recipientmask", "programscheduledigest", "launcherrevision", "localinputdigest",
                "localpartyindex", "localpartyrank", "tlsenabled", "tlscertificatedigests",
                "launchconfigdigest", "knowndisclosure", "transportsecurity", "framework",
                "frameworkversion", "strategy", "aggregator",
                "protocolmessages", "source", "observations", "counter", "bytes", "reportedbytes",
                "summarydigest", "transcriptdigestavailable", "invalidengineevidence",
                "launchersilent", "serverquerykeylogcheck", "kusciacontextdigest", "kusciadeploymentid",
                "device", "scheme", "keysizebits", "keykeeper", "ciphertextevaluator", "operation",
                "fixedpointscale", "freshkeyperattempt", "alignmentprotocol", "intersectioncount",
                "trainer", "securedevice", "labelholder", "modelshardsstayatowner", "released",
                "executionmode", "transport", "servingid", "localmodel",
                "modelreference", "modeldigest", "modelbytes", "modelfiles",
                "participants", "participantstatuses", "partyid", "role", "events", "messages",
                "messagetype", "messagecode", "messagedigest", "messagedigests", "payloadbytes",
                "resultdigest", "resultreference", "evidencereference", "workloadrefs", "taskids",
                "namespace", "pod", "job", "attemptid"));
        Object value = sanitizeEvidenceValue(raw, allowed);
        if (!(value instanceof Map)) return Collections.emptyMap();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private Object sanitizeEvidenceValue(Object value, Set<String> allowed) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                Object sanitized = sanitizeEvidenceValue(item, allowed);
                if (sanitized != null) result.add(sanitized);
            }
            return result;
        }
        if (!(value instanceof Map)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String key = String.valueOf(entry.getKey());
            String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            boolean partyObject = normalized.matches("[abc]") && entry.getValue() instanceof Map;
            if (!allowed.contains(normalized) && !partyObject) continue;
            Object sanitized = "tlscertificatedigests".equals(normalized)
                    ? sanitizeCertificateDigests(entry.getValue())
                    : sanitizeEvidenceValue(entry.getValue(), allowed);
            // A null reportedBytes means the engine supplied no counter; retain that
            // distinction from a measured zero and preserve the summary's input shape.
            if (sanitized != null || entry.getValue() == null) result.put(key, sanitized);
        }
        return result;
    }

    private Object sanitizeCertificateDigests(Object value) {
        if (!(value instanceof Map)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object digest = entry.getValue();
            // These are public certificate fingerprints emitted by the fixed MP-SPDZ
            // adapter, not a general file/path dictionary or certificate payload.
            if (name.matches("P[0-2]\\.pem") && digest instanceof String
                    && ((String) digest).matches("(?:sha256:)?[0-9a-fA-F]{64}")) {
                result.put(name, digest);
            }
        }
        return result;
    }

    private void finish(JobRecord job, JobStatus status, String code, String reason) {
        if (job == null || JobStatus.valueOf(job.getStatus()).terminal()) return;
        String safe = truncate(reason, 1024);
        if (mapper.finish(job.getJobId(), job.getCurrentAttemptId(), status.name(), code, safe) == 1) {
            mapper.finishAttempt(job.getCurrentAttemptId(), status.name(), code, safe);
            event(job.getJobId(), job.getCurrentAttemptId(), null, "COMPLETE", status.name(),
                    code == null ? "PRIVACY_JOB_FINISHED" : code, null, null, safe);
        }
    }

    private void submit(String jobId, String attemptId) {
        try {
            executor.execute(() -> executeAttempt(jobId, attemptId));
        } catch (RuntimeException ex) {
            finish(mapper.findJob(jobId), JobStatus.FAILED, "EXECUTOR_REJECTED",
                    "privacy compute executor could not accept the attempt");
        }
    }

    private void safeCancel(JobRecord job, String reason) {
        try {
            providers.require(ProviderType.valueOf(job.getProvider())).cancel(job.getExternalJobId(), reason);
        } catch (RuntimeException ignored) {
            // The durable cancellation event is authoritative; reconciliation can observe remote cleanup separately.
        }
    }

    private JobView requireJob(String jobId) { return toView(requireRecord(jobId)); }

    private JobRecord requireRecord(String jobId) {
        if (blank(jobId)) throw RegistrationException.invalid("PRIVACY_JOB_ID_REQUIRED", "jobId is required");
        JobRecord job = mapper.findJob(jobId);
        if (job == null) throw RegistrationException.notFound("PRIVACY_JOB_NOT_FOUND", "privacy job was not found");
        return job;
    }

    private JobView toView(JobRecord row) {
        JobSpec spec = readSpec(row.getSpecJson());
        JobView value = new JobView();
        value.setJobId(row.getJobId());
        value.setRequestId(row.getRequestId());
        value.setAttemptId(row.getCurrentAttemptId());
        value.setAttemptNo(row.getCurrentAttemptNo());
        value.setTemplateId(row.getTemplateId());
        value.setProvider(ProviderType.valueOf(row.getProvider()));
        value.setSecurityProfile(row.getSecurityProfile());
        value.setStatus(JobStatus.valueOf(row.getStatus()));
        value.setInitiator(row.getInitiator());
        value.setParticipants(spec.getParticipants());
        List<ApprovalView> approvalViews = new ArrayList<>();
        for (ApprovalRecord approval : mapper.findApprovals(row.getJobId(), row.getCurrentAttemptId())) {
            ApprovalView item = new ApprovalView();
            item.setParticipantId(approval.getParticipantId());
            item.setDecision(approval.getDecision());
            item.setReason(approval.getReason());
            item.setDecidedAt(approval.getDecidedAt());
            approvalViews.add(item);
        }
        value.setApprovals(approvalViews);
        value.setResultRecipients(spec.getResultRecipients());
        value.setTimeoutSeconds(row.getTimeoutSeconds());
        value.setEnginePolicy(spec.getEnginePolicy());
        value.setSpecDigest(row.getSpecDigest());
        value.setProtocolVersion(row.getProtocolVersion());
        value.setImageDigest(row.getImageDigest());
        value.setFailureCode(row.getFailureCode());
        value.setFailureReason(row.getFailureReason());
        value.setCreatedAt(row.getCreatedAt());
        value.setQueuedAt(row.getQueuedAt());
        value.setStartedAt(row.getStartedAt());
        value.setCompletedAt(row.getCompletedAt());
        return value;
    }

    private String requireParticipantPrincipal(JobRecord job, String principal, String requestedParticipant) {
        String actor = normalizePrincipal(principal);
        String participant = normalizePrincipal(requestedParticipant);
        if (!actor.equals(participant)) {
            throw new RegistrationException(HttpStatus.FORBIDDEN, "APPROVAL_PRINCIPAL_MISMATCH",
                    "principal can decide only for itself");
        }
        if (!mapper.findParticipantIds(job.getJobId()).contains(participant)) {
            throw new RegistrationException(HttpStatus.FORBIDDEN, "PRINCIPAL_NOT_PARTICIPANT",
                    "principal is not a task participant");
        }
        return participant;
    }

    private void requireParticipantOrInitiator(JobRecord job, String principal) {
        String actor = normalizePrincipal(principal);
        if (!job.getInitiator().equals(actor) && !mapper.findParticipantIds(job.getJobId()).contains(actor)) {
            throw new RegistrationException(HttpStatus.FORBIDDEN, "PRIVACY_JOB_ACCESS_DENIED",
                    "principal is not a task participant");
        }
    }

    private void requireAwaitingApproval(JobRecord job) {
        if (!JobStatus.AWAITING_APPROVAL.name().equals(job.getStatus())) {
            throw RegistrationException.conflict("APPROVAL_STATE_INVALID",
                    "job is not awaiting participant approval");
        }
    }

    private String normalizePrincipal(String value) {
        if (blank(value)) throw RegistrationException.invalid("PRIVACY_PRINCIPAL_REQUIRED",
                "authenticated privacy party is required");
        String result = value.trim().toUpperCase();
        if (!result.matches("[A-Z0-9_-]{1,32}")) {
            throw RegistrationException.invalid("PRIVACY_PRINCIPAL_INVALID", "invalid privacy principal");
        }
        return result;
    }

    private String normalizeStatus(String status) {
        if (blank(status)) return null;
        try {
            return JobStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw RegistrationException.invalid("PRIVACY_STATUS_INVALID", "unknown privacy job status");
        }
    }

    private int normalizeLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > 200) {
            throw RegistrationException.invalid("PRIVACY_LIMIT_INVALID", "limit must be between 1 and 200");
        }
        return limit;
    }

    private void event(String jobId, String attemptId, String participantId, String phase,
                       String status, String messageCode, Long payloadBytes,
                       String messageDigest, String detail) {
        EventRecord event = new EventRecord();
        event.setJobId(jobId);
        event.setAttemptId(attemptId);
        event.setParticipantId(participantId);
        event.setPhase(phase);
        event.setStatus(status);
        event.setMessageCode(messageCode);
        event.setPayloadBytes(payloadBytes);
        event.setMessageDigest(messageDigest);
        event.setDetail(truncate(detail, 1024));
        mapper.insertEvent(event);
    }

    private JobSpec readSpec(String value) {
        try {
            return objectMapper.readValue(value, JobSpec.class);
        } catch (Exception ex) {
            throw new IllegalStateException("stored privacy job specification is invalid", ex);
        }
    }

    private List<String> readList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("stored result recipients are invalid", ex);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            throw new IllegalStateException("stored provider evidence is invalid", ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to serialize privacy metadata", ex);
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private String sanitizeReason(String reason) {
        if (blank(reason)) return null;
        return truncate(reason.replaceAll("[\\r\\n\\t]", " "), 512);
    }

    private String safeFailure(Throwable ex) {
        String value = ex == null ? null : ex.getMessage();
        if (blank(value) && ex != null) value = ex.getClass().getSimpleName();
        return truncate(value == null ? "unknown failure" : value.replaceAll("[\\r\\n\\t]", " "), 512);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String emptyDefault(String value, String fallback) { return blank(value) ? fallback : value; }
    private String digestWire(String value) {
        if (blank(value)) return value;
        return value.startsWith("sha256:") ? value.toLowerCase() : "sha256:" + value.toLowerCase();
    }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static class ProviderFailure extends RuntimeException {
        private final String code;
        private ProviderFailure(String code, String message) { super(message); this.code = code; }
    }
}
