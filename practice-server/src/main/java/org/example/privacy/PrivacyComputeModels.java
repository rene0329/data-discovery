package org.example.privacy;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire and persistence models for the privacy-computing control plane.
 *
 * <p>The control plane intentionally stores only immutable input metadata and result references.
 * Raw participant data, shares, masks and private keys are never fields of these models.</p>
 */
public final class PrivacyComputeModels {
    private PrivacyComputeModels() { }

    public enum ProviderType {
        KUSCIA_SECRETFLOW,
        KUSCIA_APSI,
        KUSCIA_SFL,
        MP_SPDZ,
        FLOWER_RESERVED
    }

    public enum CapabilityStatus { AVAILABLE, UNAVAILABLE, RESERVED }

    public enum JobStatus {
        AWAITING_APPROVAL,
        QUEUED,
        PREPARING,
        RUNNING,
        FINALIZING,
        SUCCEEDED,
        FAILED,
        ABORTED,
        CANCELLED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == ABORTED || this == CANCELLED;
        }
    }

    public enum ProviderRunStatus {
        DISPATCHING, QUEUED, PREPARING, RUNNING, FINALIZING, SUCCEEDED, FAILED, ABORTED, CANCELLED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProviderCapability {
        private ProviderType provider;
        private String displayName;
        private CapabilityStatus status;
        private String version;
        private String imageDigest;
        private List<String> securityProfiles = new ArrayList<>();
        private List<String> operations = new ArrayList<>();
        private boolean experimental;
        private String reason;

        public ProviderType getProvider() { return provider; }
        public void setProvider(ProviderType provider) { this.provider = provider; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public CapabilityStatus getStatus() { return status; }
        public void setStatus(CapabilityStatus status) { this.status = status; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public String getImageDigest() { return imageDigest; }
        public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }
        public List<String> getSecurityProfiles() { return securityProfiles; }
        public void setSecurityProfiles(List<String> securityProfiles) { this.securityProfiles = securityProfiles; }
        public List<String> getOperations() { return operations; }
        public void setOperations(List<String> operations) { this.operations = operations; }
        public boolean isExperimental() { return experimental; }
        public void setExperimental(boolean experimental) { this.experimental = experimental; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TemplateDefinition {
        private String templateId;
        private String displayName;
        private ProviderType provider;
        private String operation;
        private String securityProfile;
        private int participantCount;
        private Map<String, String> requiredRoles = new LinkedHashMap<>();
        private List<ParticipantSlot> participantSlots = new ArrayList<>();
        private boolean available;
        private boolean experimental;
        private String leakageDisclosure;
        private List<String> supportedResults = new ArrayList<>();
        private int maxTimeoutSeconds;
        private String protocolVersion;
        private String imageDigest;
        private String unavailableReason;

        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public ProviderType getProvider() { return provider; }
        public void setProvider(ProviderType provider) { this.provider = provider; }
        public String getOperation() { return operation; }
        public void setOperation(String operation) { this.operation = operation; }
        public String getSecurityProfile() { return securityProfile; }
        public void setSecurityProfile(String securityProfile) { this.securityProfile = securityProfile; }
        public int getParticipantCount() { return participantCount; }
        public void setParticipantCount(int participantCount) { this.participantCount = participantCount; }
        public Map<String, String> getRequiredRoles() { return requiredRoles; }
        public void setRequiredRoles(Map<String, String> requiredRoles) { this.requiredRoles = requiredRoles; }
        public List<ParticipantSlot> getParticipantSlots() { return participantSlots; }
        public void setParticipantSlots(List<ParticipantSlot> participantSlots) { this.participantSlots = participantSlots; }
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public boolean isExperimental() { return experimental; }
        public void setExperimental(boolean experimental) { this.experimental = experimental; }
        public String getLeakageDisclosure() { return leakageDisclosure; }
        public void setLeakageDisclosure(String leakageDisclosure) { this.leakageDisclosure = leakageDisclosure; }
        public List<String> getSupportedResults() { return supportedResults; }
        public void setSupportedResults(List<String> supportedResults) { this.supportedResults = supportedResults; }
        public int getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
        public void setMaxTimeoutSeconds(int maxTimeoutSeconds) { this.maxTimeoutSeconds = maxTimeoutSeconds; }
        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
        public String getImageDigest() { return imageDigest; }
        public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }
        public String getUnavailableReason() { return unavailableReason; }
        public void setUnavailableReason(String unavailableReason) { this.unavailableReason = unavailableReason; }
    }

    public static class ParticipantSlot {
        private String slotId;
        private String role;
        private String label;
        public ParticipantSlot() { }
        public ParticipantSlot(String slotId, String role, String label) {
            this.slotId = slotId;
            this.role = role;
            this.label = label;
        }
        public String getSlotId() { return slotId; }
        public void setSlotId(String slotId) { this.slotId = slotId; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    /** Caller supplied dataset binding. Party/runtime identities are resolved by the server. */
    public static class InputSpec {
        private String slotId;
        private Long datasetId;
        private String datasetVersion;
        private List<String> fields = new ArrayList<>();
        public String getSlotId() { return slotId; }
        public void setSlotId(String slotId) { this.slotId = slotId; }
        public Long getDatasetId() { return datasetId; }
        public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
        public String getDatasetVersion() { return datasetVersion; }
        public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
        public List<String> getFields() { return fields; }
        public void setFields(List<String> fields) { this.fields = fields; }
        @JsonAnySetter
        public void rejectUnknownField(String name, Object ignored) {
            throw new IllegalArgumentException("unsupported input field: " + name);
        }
    }

    /**
     * One runtime party. {@code ownerDomainId/Code/Name} identify the collaboration
     * domain that contributes this input: the single enabled domain the dataset
     * was located in when the job was created (frozen into the spec). Any enabled
     * DATA_OWNER of that domain may approve the input.
     *
     * <p>Specs frozen before the domain model also carry the retired per-dataset
     * holder ({@code ownerUserId}, {@code ownerUsername}); those keys are accepted
     * and dropped so stored jobs and idempotent replays still deserialize.
     */
    @JsonIgnoreProperties({"ownerUserId", "ownerUsername"})
    public static class ParticipantSpec {
        private String slotId;
        private String partyId;
        private String role;
        private Long ownerDomainId;
        private String ownerDomainCode;
        private String ownerDomainName;
        private String datasetId;
        private String datasetVersion;
        private String datasetSha256;
        private Long authoritativeSizeBytes;
        private String schemaDigest;
        private List<Map<String, Object>> frozenSchema = new ArrayList<>();
        private List<String> fields = new ArrayList<>();

        public String getSlotId() { return slotId; }
        public void setSlotId(String slotId) { this.slotId = slotId; }
        public String getPartyId() { return partyId; }
        public void setPartyId(String partyId) { this.partyId = partyId; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public Long getOwnerDomainId() { return ownerDomainId; }
        public void setOwnerDomainId(Long ownerDomainId) { this.ownerDomainId = ownerDomainId; }
        public String getOwnerDomainCode() { return ownerDomainCode; }
        public void setOwnerDomainCode(String ownerDomainCode) { this.ownerDomainCode = ownerDomainCode; }
        public String getOwnerDomainName() { return ownerDomainName; }
        public void setOwnerDomainName(String ownerDomainName) { this.ownerDomainName = ownerDomainName; }
        public String getDatasetId() { return datasetId; }
        public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
        public String getDatasetVersion() { return datasetVersion; }
        public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
        public String getDatasetSha256() { return datasetSha256; }
        public void setDatasetSha256(String datasetSha256) { this.datasetSha256 = datasetSha256; }
        public Long getAuthoritativeSizeBytes() { return authoritativeSizeBytes; }
        public void setAuthoritativeSizeBytes(Long authoritativeSizeBytes) { this.authoritativeSizeBytes = authoritativeSizeBytes; }
        public String getSchemaDigest() { return schemaDigest; }
        public void setSchemaDigest(String schemaDigest) { this.schemaDigest = schemaDigest; }
        public List<Map<String, Object>> getFrozenSchema() { return frozenSchema; }
        public void setFrozenSchema(List<Map<String, Object>> frozenSchema) { this.frozenSchema = frozenSchema; }
        public List<String> getFields() { return fields; }
        public void setFields(List<String> fields) { this.fields = fields; }
        @JsonAnySetter
        public void rejectUnknownField(String name, Object ignored) {
            throw new IllegalArgumentException("unsupported participant field: " + name);
        }
    }

    public static class JobSpec {
        private String templateId;
        private String securityProfile;
        private List<InputSpec> inputs = new ArrayList<>();
        private List<ParticipantSpec> participants = new ArrayList<>();
        private List<String> resultRecipients = new ArrayList<>();
        private Integer timeoutSeconds;
        private Map<String, Object> enginePolicy = new LinkedHashMap<>();

        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public String getSecurityProfile() { return securityProfile; }
        public void setSecurityProfile(String securityProfile) { this.securityProfile = securityProfile; }
        public List<InputSpec> getInputs() { return inputs; }
        public void setInputs(List<InputSpec> inputs) { this.inputs = inputs; }
        public List<ParticipantSpec> getParticipants() { return participants; }
        public void setParticipants(List<ParticipantSpec> participants) { this.participants = participants; }
        public List<String> getResultRecipients() { return resultRecipients; }
        public void setResultRecipients(List<String> resultRecipients) { this.resultRecipients = resultRecipients; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public Map<String, Object> getEnginePolicy() { return enginePolicy; }
        public void setEnginePolicy(Map<String, Object> enginePolicy) { this.enginePolicy = enginePolicy; }
        @JsonAnySetter
        public void rejectUnknownField(String name, Object ignored) {
            throw new IllegalArgumentException("unsupported privacy job field: " + name);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PreflightResult {
        private boolean valid;
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private TemplateDefinition template;
        private String specDigest;

        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        public List<String> getWarnings() { return warnings; }
        public void setWarnings(List<String> warnings) { this.warnings = warnings; }
        public TemplateDefinition getTemplate() { return template; }
        public void setTemplate(TemplateDefinition template) { this.template = template; }
        public String getSpecDigest() { return specDigest; }
        public void setSpecDigest(String specDigest) { this.specDigest = specDigest; }
    }

    /** Database row; JSON columns are expanded by the service before returning JobView. */
    public static class JobRecord {
        private String jobId;
        private String requestId;
        private String currentAttemptId;
        private Integer currentAttemptNo;
        private String templateId;
        private String provider;
        private String securityProfile;
        private String status;
        private String initiator;
        private Long initiatorUserId;
        private String resultRecipientsJson;
        private Integer timeoutSeconds;
        private String enginePolicyJson;
        private String specJson;
        private String specDigest;
        private String protocolVersion;
        private String imageDigest;
        private String externalJobId;
        private String failureCode;
        private String failureReason;
        private LocalDateTime createdAt;
        private LocalDateTime queuedAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;

        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public String getCurrentAttemptId() { return currentAttemptId; }
        public void setCurrentAttemptId(String currentAttemptId) { this.currentAttemptId = currentAttemptId; }
        public Integer getCurrentAttemptNo() { return currentAttemptNo; }
        public void setCurrentAttemptNo(Integer currentAttemptNo) { this.currentAttemptNo = currentAttemptNo; }
        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getSecurityProfile() { return securityProfile; }
        public void setSecurityProfile(String securityProfile) { this.securityProfile = securityProfile; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getInitiator() { return initiator; }
        public void setInitiator(String initiator) { this.initiator = initiator; }
        public Long getInitiatorUserId() { return initiatorUserId; }
        public void setInitiatorUserId(Long initiatorUserId) { this.initiatorUserId = initiatorUserId; }
        public String getResultRecipientsJson() { return resultRecipientsJson; }
        public void setResultRecipientsJson(String resultRecipientsJson) { this.resultRecipientsJson = resultRecipientsJson; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public String getEnginePolicyJson() { return enginePolicyJson; }
        public void setEnginePolicyJson(String enginePolicyJson) { this.enginePolicyJson = enginePolicyJson; }
        public String getSpecJson() { return specJson; }
        public void setSpecJson(String specJson) { this.specJson = specJson; }
        public String getSpecDigest() { return specDigest; }
        public void setSpecDigest(String specDigest) { this.specDigest = specDigest; }
        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
        public String getImageDigest() { return imageDigest; }
        public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }
        public String getExternalJobId() { return externalJobId; }
        public void setExternalJobId(String externalJobId) { this.externalJobId = externalJobId; }
        public String getFailureCode() { return failureCode; }
        public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
        public String getFailureReason() { return failureReason; }
        public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getQueuedAt() { return queuedAt; }
        public void setQueuedAt(LocalDateTime queuedAt) { this.queuedAt = queuedAt; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JobView {
        private String jobId;
        private String requestId;
        private String attemptId;
        private Integer attemptNo;
        private String templateId;
        private ProviderType provider;
        private String securityProfile;
        private JobStatus status;
        private String initiator;
        private Long initiatorUserId;
        private List<ParticipantSpec> participants = new ArrayList<>();
        private List<ApprovalView> approvals = new ArrayList<>();
        private List<String> resultRecipients = new ArrayList<>();
        private Integer timeoutSeconds;
        private Map<String, Object> enginePolicy = new LinkedHashMap<>();
        private String specDigest;
        private String protocolVersion;
        private String imageDigest;
        private String failureCode;
        private String failureReason;
        private LocalDateTime createdAt;
        private LocalDateTime queuedAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;

        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getRequestId() { return requestId; }
        public void setRequestId(String requestId) { this.requestId = requestId; }
        public String getAttemptId() { return attemptId; }
        public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
        public Integer getAttemptNo() { return attemptNo; }
        public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }
        public ProviderType getProvider() { return provider; }
        public void setProvider(ProviderType provider) { this.provider = provider; }
        public String getSecurityProfile() { return securityProfile; }
        public void setSecurityProfile(String securityProfile) { this.securityProfile = securityProfile; }
        public JobStatus getStatus() { return status; }
        public void setStatus(JobStatus status) { this.status = status; }
        public String getInitiator() { return initiator; }
        public void setInitiator(String initiator) { this.initiator = initiator; }
        public Long getInitiatorUserId() { return initiatorUserId; }
        public void setInitiatorUserId(Long initiatorUserId) { this.initiatorUserId = initiatorUserId; }
        public List<ParticipantSpec> getParticipants() { return participants; }
        public void setParticipants(List<ParticipantSpec> participants) { this.participants = participants; }
        public List<ApprovalView> getApprovals() { return approvals; }
        public void setApprovals(List<ApprovalView> approvals) { this.approvals = approvals; }
        public List<String> getResultRecipients() { return resultRecipients; }
        public void setResultRecipients(List<String> resultRecipients) { this.resultRecipients = resultRecipients; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public Map<String, Object> getEnginePolicy() { return enginePolicy; }
        public void setEnginePolicy(Map<String, Object> enginePolicy) { this.enginePolicy = enginePolicy; }
        public String getSpecDigest() { return specDigest; }
        public void setSpecDigest(String specDigest) { this.specDigest = specDigest; }
        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
        public String getImageDigest() { return imageDigest; }
        public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }
        public String getFailureCode() { return failureCode; }
        public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
        public String getFailureReason() { return failureReason; }
        public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public LocalDateTime getQueuedAt() { return queuedAt; }
        public void setQueuedAt(LocalDateTime queuedAt) { this.queuedAt = queuedAt; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    }

    public static class DecisionRequest {
        private String reason;
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class ActionRequest {
        private String reason;
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class EventRecord {
        private Long eventId;
        private String jobId;
        private String attemptId;
        private String participantId;
        private String phase;
        private String status;
        private String messageCode;
        private Long payloadBytes;
        private String messageDigest;
        private String detail;
        private LocalDateTime createdAt;
        public Long getEventId() { return eventId; }
        public void setEventId(Long eventId) { this.eventId = eventId; }
        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getAttemptId() { return attemptId; }
        public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
        public String getParticipantId() { return participantId; }
        public void setParticipantId(String participantId) { this.participantId = participantId; }
        public String getPhase() { return phase; }
        public void setPhase(String phase) { this.phase = phase; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMessageCode() { return messageCode; }
        public void setMessageCode(String messageCode) { this.messageCode = messageCode; }
        public Long getPayloadBytes() { return payloadBytes; }
        public void setPayloadBytes(Long payloadBytes) { this.payloadBytes = payloadBytes; }
        public String getMessageDigest() { return messageDigest; }
        public void setMessageDigest(String messageDigest) { this.messageDigest = messageDigest; }
        public String getDetail() { return detail; }
        public void setDetail(String detail) { this.detail = detail; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    /**
     * One participant's decision for one attempt. The approver is whoever decided
     * (the initiator for an auto-approved own-domain input); it is null while the
     * decision is PENDING. ownerDomainId/Code is the participant's domain, whose
     * enabled DATA_OWNER users may decide.
     */
    public static class ApprovalRecord {
        private String jobId;
        private String attemptId;
        private String participantId;
        private Long ownerDomainId;
        private String ownerDomainCode;
        private Long approverUserId;
        private String approverUsername;
        private String inputSnapshotDigest;
        private String decision;
        private String reason;
        private String decisionSignature;
        private LocalDateTime decidedAt;
        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getAttemptId() { return attemptId; }
        public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
        public String getParticipantId() { return participantId; }
        public void setParticipantId(String participantId) { this.participantId = participantId; }
        public Long getOwnerDomainId() { return ownerDomainId; }
        public void setOwnerDomainId(Long ownerDomainId) { this.ownerDomainId = ownerDomainId; }
        public String getOwnerDomainCode() { return ownerDomainCode; }
        public void setOwnerDomainCode(String ownerDomainCode) { this.ownerDomainCode = ownerDomainCode; }
        public Long getApproverUserId() { return approverUserId; }
        public void setApproverUserId(Long approverUserId) { this.approverUserId = approverUserId; }
        public String getApproverUsername() { return approverUsername; }
        public void setApproverUsername(String approverUsername) { this.approverUsername = approverUsername; }
        public String getInputSnapshotDigest() { return inputSnapshotDigest; }
        public void setInputSnapshotDigest(String inputSnapshotDigest) { this.inputSnapshotDigest = inputSnapshotDigest; }
        public String getDecision() { return decision; }
        public void setDecision(String decision) { this.decision = decision; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getDecisionSignature() { return decisionSignature; }
        public void setDecisionSignature(String decisionSignature) { this.decisionSignature = decisionSignature; }
        public LocalDateTime getDecidedAt() { return decidedAt; }
        public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    }

    /** See {@link ApprovalRecord}: approver fields stay null until someone decides. */
    public static class ApprovalView {
        private String participantId;
        private Long ownerDomainId;
        private String ownerDomainCode;
        private String ownerDomainName;
        private Long approverUserId;
        private String approverUsername;
        private String inputSnapshotDigest;
        private String decision;
        private String reason;
        private LocalDateTime decidedAt;
        public String getParticipantId() { return participantId; }
        public void setParticipantId(String participantId) { this.participantId = participantId; }
        public Long getOwnerDomainId() { return ownerDomainId; }
        public void setOwnerDomainId(Long ownerDomainId) { this.ownerDomainId = ownerDomainId; }
        public String getOwnerDomainCode() { return ownerDomainCode; }
        public void setOwnerDomainCode(String ownerDomainCode) { this.ownerDomainCode = ownerDomainCode; }
        public String getOwnerDomainName() { return ownerDomainName; }
        public void setOwnerDomainName(String ownerDomainName) { this.ownerDomainName = ownerDomainName; }
        public Long getApproverUserId() { return approverUserId; }
        public void setApproverUserId(Long approverUserId) { this.approverUserId = approverUserId; }
        public String getApproverUsername() { return approverUsername; }
        public void setApproverUsername(String approverUsername) { this.approverUsername = approverUsername; }
        public String getInputSnapshotDigest() { return inputSnapshotDigest; }
        public void setInputSnapshotDigest(String inputSnapshotDigest) { this.inputSnapshotDigest = inputSnapshotDigest; }
        public String getDecision() { return decision; }
        public void setDecision(String decision) { this.decision = decision; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public LocalDateTime getDecidedAt() { return decidedAt; }
        public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    }

    public static class InputSnapshotRecord {
        private Long snapshotId;
        private String jobId;
        private String partyId;
        private String slotId;
        private Long ownerDomainId;
        private Long datasetId;
        private String datasetCode;
        private String datasetVersion;
        private String digestAlgorithm;
        private String digestValue;
        private Long sizeBytes;
        private String schemaJson;
        private String schemaDigest;
        private String fieldsJson;
        public Long getSnapshotId() { return snapshotId; }
        public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getPartyId() { return partyId; }
        public void setPartyId(String partyId) { this.partyId = partyId; }
        public String getSlotId() { return slotId; }
        public void setSlotId(String slotId) { this.slotId = slotId; }
        public Long getOwnerDomainId() { return ownerDomainId; }
        public void setOwnerDomainId(Long ownerDomainId) { this.ownerDomainId = ownerDomainId; }
        public Long getDatasetId() { return datasetId; }
        public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
        public String getDatasetCode() { return datasetCode; }
        public void setDatasetCode(String datasetCode) { this.datasetCode = datasetCode; }
        public String getDatasetVersion() { return datasetVersion; }
        public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
        public String getDigestAlgorithm() { return digestAlgorithm; }
        public void setDigestAlgorithm(String digestAlgorithm) { this.digestAlgorithm = digestAlgorithm; }
        public String getDigestValue() { return digestValue; }
        public void setDigestValue(String digestValue) { this.digestValue = digestValue; }
        public Long getSizeBytes() { return sizeBytes; }
        public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
        public String getSchemaJson() { return schemaJson; }
        public void setSchemaJson(String schemaJson) { this.schemaJson = schemaJson; }
        public String getSchemaDigest() { return schemaDigest; }
        public void setSchemaDigest(String schemaDigest) { this.schemaDigest = schemaDigest; }
        public String getFieldsJson() { return fieldsJson; }
        public void setFieldsJson(String fieldsJson) { this.fieldsJson = fieldsJson; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResultView {
        private String jobId;
        private String attemptId;
        private String status;
        private String resultReference;
        private String resultDigest;
        private String mediaType;
        private Object result;
        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getAttemptId() { return attemptId; }
        public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getResultReference() { return resultReference; }
        public void setResultReference(String resultReference) { this.resultReference = resultReference; }
        public String getResultDigest() { return resultDigest; }
        public void setResultDigest(String resultDigest) { this.resultDigest = resultDigest; }
        public String getMediaType() { return mediaType; }
        public void setMediaType(String mediaType) { this.mediaType = mediaType; }
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
    }

    public static class ResultRecord {
        private String jobId;
        private String attemptId;
        private String resultReference;
        private String resultDigest;
        private String mediaType;
        private LocalDateTime createdAt;
        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getAttemptId() { return attemptId; }
        public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
        public String getResultReference() { return resultReference; }
        public void setResultReference(String resultReference) { this.resultReference = resultReference; }
        public String getResultDigest() { return resultDigest; }
        public void setResultDigest(String resultDigest) { this.resultDigest = resultDigest; }
        public String getMediaType() { return mediaType; }
        public void setMediaType(String mediaType) { this.mediaType = mediaType; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    public static class EvidenceRecord {
        private String jobId;
        private String attemptId;
        private String evidenceReference;
        private String evidenceJson;
        private String evidenceDigest;
        private LocalDateTime createdAt;
        public String getJobId() { return jobId; }
        public void setJobId(String jobId) { this.jobId = jobId; }
        public String getAttemptId() { return attemptId; }
        public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
        public String getEvidenceReference() { return evidenceReference; }
        public void setEvidenceReference(String evidenceReference) { this.evidenceReference = evidenceReference; }
        public String getEvidenceJson() { return evidenceJson; }
        public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
        public String getEvidenceDigest() { return evidenceDigest; }
        public void setEvidenceDigest(String evidenceDigest) { this.evidenceDigest = evidenceDigest; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    public static class ProviderSubmission {
        private String externalJobId;
        private ProviderRunStatus status;
        private String failureCode;
        @JsonAlias("failureMessage")
        private String failureReason;
        private String resultReference;
        private String resultDigest;
        private String resultMediaType;
        public String getExternalJobId() { return externalJobId; }
        public void setExternalJobId(String externalJobId) { this.externalJobId = externalJobId; }
        public ProviderRunStatus getStatus() { return status; }
        public void setStatus(ProviderRunStatus status) { this.status = status; }
        public String getFailureCode() { return failureCode; }
        public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
        public String getFailureReason() { return failureReason; }
        public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
        public String getResultReference() { return resultReference; }
        public void setResultReference(String resultReference) { this.resultReference = resultReference; }
        public String getResultDigest() { return resultDigest; }
        public void setResultDigest(String resultDigest) { this.resultDigest = resultDigest; }
        public String getResultMediaType() { return resultMediaType; }
        public void setResultMediaType(String resultMediaType) { this.resultMediaType = resultMediaType; }
    }

    /** Ephemeral runtime-only descriptor. Instances must never be persisted or logged. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StagingInput {
        private String partyId;
        private String datasetId;
        private String datasetVersion;
        private String expectedSha256;
        private Long expectedSize;
        private List<Map<String, Object>> expectedSchema = new ArrayList<>();
        private String expectedSchemaDigest;
        private Integer nodeId;
        private String nodeName;
        private String agentBaseUrl;
        private String sourcePath;
        private String tokenType;
        private String token;
        private java.time.Instant expiresAt;
        public String getPartyId() { return partyId; }
        public void setPartyId(String partyId) { this.partyId = partyId; }
        public String getDatasetId() { return datasetId; }
        public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
        public String getDatasetVersion() { return datasetVersion; }
        public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
        public String getExpectedSha256() { return expectedSha256; }
        public void setExpectedSha256(String expectedSha256) { this.expectedSha256 = expectedSha256; }
        public Long getExpectedSize() { return expectedSize; }
        public void setExpectedSize(Long expectedSize) { this.expectedSize = expectedSize; }
        public List<Map<String, Object>> getExpectedSchema() { return expectedSchema; }
        public void setExpectedSchema(List<Map<String, Object>> expectedSchema) { this.expectedSchema = expectedSchema; }
        public String getExpectedSchemaDigest() { return expectedSchemaDigest; }
        public void setExpectedSchemaDigest(String expectedSchemaDigest) { this.expectedSchemaDigest = expectedSchemaDigest; }
        public Integer getNodeId() { return nodeId; }
        public void setNodeId(Integer nodeId) { this.nodeId = nodeId; }
        public String getNodeName() { return nodeName; }
        public void setNodeName(String nodeName) { this.nodeName = nodeName; }
        public String getAgentBaseUrl() { return agentBaseUrl; }
        public void setAgentBaseUrl(String agentBaseUrl) { this.agentBaseUrl = agentBaseUrl; }
        public String getSourcePath() { return sourcePath; }
        public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public java.time.Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(java.time.Instant expiresAt) { this.expiresAt = expiresAt; }
    }
}
