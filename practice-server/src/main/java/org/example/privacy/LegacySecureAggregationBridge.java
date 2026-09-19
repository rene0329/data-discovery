package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.RegistrationException;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.JobStatus;
import org.example.privacy.PrivacyComputeModels.JobView;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.ResultView;
import org.example.security.aggregation.coordinator.SecureAggregationMessageEvent;
import org.example.security.aggregation.coordinator.SecureAggregationRun;
import org.example.service.ApiIdempotencyService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Compatibility facade: old POST shape, new malicious-secure task underneath. */
@Component
public class LegacySecureAggregationBridge {
    private final PrivacyComputeService service;
    private final PrivacyPartyAuthenticator authenticator;
    private final ApiIdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public LegacySecureAggregationBridge(PrivacyComputeService service,
                                         PrivacyPartyAuthenticator authenticator,
                                         ApiIdempotencyService idempotency,
                                         ObjectMapper objectMapper,
                                         Environment environment) {
        this.service = service;
        this.authenticator = authenticator;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    public SecureAggregationRun start(String authorization, String suppliedRequestId, JobSpec suppliedSpec) {
        String principal = authenticator.authenticate(authorization);
        String requestId = suppliedRequestId == null || suppliedRequestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : suppliedRequestId.trim();
        JobSpec spec = suppliedSpec == null ? configuredSpec(principal) : suppliedSpec;
        if (!"secure-sum-3p-v1".equals(spec.getTemplateId())) {
            throw RegistrationException.invalid("LEGACY_TEMPLATE_INVALID",
                    "legacy secure-aggregation endpoint accepts only secure-sum-3p-v1");
        }
        JobView result = idempotency.execute("PRIVACY_JOB", "LEGACY_SECURE_SUM_CREATE", requestId,
                principal, spec, JobView.class, () -> service.create(spec, requestId, principal),
                JobView::getJobId);
        return toLegacy(result, principal);
    }

    public SecureAggregationRun get(String runId, String authorization) {
        String principal = authenticator.authenticate(authorization);
        return toLegacy(service.getAuthorized(runId, principal), principal);
    }

    public List<SecureAggregationMessageEvent> events(String runId, String authorization) {
        String principal = authenticator.authenticate(authorization);
        service.getAuthorized(runId, principal);
        List<SecureAggregationMessageEvent> result = new ArrayList<>();
        for (PrivacyComputeModels.EventRecord event : service.eventsAuthorized(runId, principal)) {
            SecureAggregationMessageEvent item = new SecureAggregationMessageEvent();
            item.setEventId(event.getEventId());
            item.setRunId(runId);
            item.setParticipantId(event.getParticipantId() == null ? "CONTROL" : event.getParticipantId());
            item.setDirection("CONTROL");
            item.setMessageType(event.getMessageCode());
            item.setStatus(event.getStatus());
            item.setPayloadBytes(event.getPayloadBytes() == null ? null
                    : (int) Math.min(Integer.MAX_VALUE, event.getPayloadBytes()));
            item.setErrorCode("FAILED".equals(event.getStatus()) ? event.getMessageCode() : null);
            item.setCreatedAt(event.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    private SecureAggregationRun toLegacy(JobView job, String principal) {
        SecureAggregationRun result = new SecureAggregationRun();
        result.setRunId(job.getJobId());
        result.setRequestId(job.getRequestId());
        result.setStatus(legacyStatus(job.getStatus()));
        result.setProtocolVersion(job.getProtocolVersion());
        List<String> parties = new ArrayList<>();
        for (ParticipantSpec participant : job.getParticipants()) parties.add(participant.getPartyId());
        result.setParticipantsJson(writeJson(parties));
        result.setFailureReason(job.getFailureReason());
        result.setCreatedAt(job.getCreatedAt());
        result.setStartedAt(job.getStartedAt());
        result.setCompletedAt(job.getCompletedAt());
        if (job.getStatus() == JobStatus.SUCCEEDED && job.getResultRecipients().contains(principal)) {
            ResultView fetched = service.result(job.getJobId(), principal);
            result.setFinalValue(extractSum(fetched.getResult(), principal));
        }
        return result;
    }

    private String legacyStatus(JobStatus status) {
        if (status == JobStatus.AWAITING_APPROVAL) return "PENDING";
        if (status == JobStatus.SUCCEEDED) return "COMPLETED";
        if (status == JobStatus.FAILED || status == JobStatus.ABORTED || status == JobStatus.CANCELLED) {
            return "FAILED";
        }
        return "RUNNING";
    }

    private String extractSum(Object value, String principal) {
        if (value == null) return null;
        if (value instanceof Number || value instanceof String) return String.valueOf(value);
        if (value instanceof Map) {
            Object sum = ((Map<?, ?>) value).get("sum");
            if (sum == null) sum = ((Map<?, ?>) value).get("finalValue");
            if (sum == null) sum = ((Map<?, ?>) value).get("value");
            if (sum == null && ((Map<?, ?>) value).get("partyResults") instanceof Map) {
                sum = ((Map<?, ?>) ((Map<?, ?>) value).get("partyResults")).get(principal);
                return extractSum(sum, principal);
            }
            if (sum instanceof List || sum instanceof Map) return writeJson(sum);
            return sum == null ? null : String.valueOf(sum);
        }
        return null;
    }

    private JobSpec configuredSpec(String principal) {
        JobSpec spec = new JobSpec();
        spec.setTemplateId("secure-sum-3p-v1");
        spec.setSecurityProfile("MALICIOUS_3PC_HONEST_MAJORITY");
        spec.setResultRecipients(Collections.singletonList(principal));
        for (String party : Arrays.asList("A", "B", "C")) {
            String prefix = "privacy-computing.legacy-secure-sum.parties."
                    + party.toLowerCase();
            String datasetId = environment.getProperty(prefix + ".dataset-id", "").trim();
            String version = environment.getProperty(prefix + ".dataset-version", "").trim();
            String fields = environment.getProperty(prefix + ".fields", "").trim();
            if (datasetId.isEmpty() || version.isEmpty() || fields.isEmpty()) {
                throw RegistrationException.conflict("LEGACY_MAPPING_NOT_CONFIGURED",
                        "legacy secure sum requires dataset-id, dataset-version and fields for A, B and C");
            }
            ParticipantSpec participant = new ParticipantSpec();
            participant.setPartyId(party);
            participant.setRole("PARTY");
            participant.setDatasetId(datasetId);
            participant.setDatasetVersion(version);
            List<String> fieldList = new ArrayList<>();
            for (String field : fields.split(",")) {
                if (!field.trim().isEmpty()) fieldList.add(field.trim());
            }
            participant.setFields(fieldList);
            spec.getParticipants().add(participant);
        }
        return spec;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to serialize legacy participant metadata", ex);
        }
    }
}
