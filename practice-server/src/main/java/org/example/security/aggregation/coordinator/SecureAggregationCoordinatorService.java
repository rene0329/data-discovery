package org.example.security.aggregation.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.security.aggregation.SecureAggregationProtocol;
import org.example.security.aggregation.SecureAggregationWire;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SecureAggregationCoordinatorService {
    private final SecureAggregationProperties properties;
    private final SecureAggregationWorkerClient workerClient;
    private final SecureAggregationMapper mapper;
    private final ObjectMapper objectMapper;

    public SecureAggregationCoordinatorService(SecureAggregationProperties properties,
                                               SecureAggregationWorkerClient workerClient,
                                               SecureAggregationMapper mapper,
                                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.workerClient = workerClient;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public SecureAggregationRun start(String suppliedRequestId) {
        String requestId = suppliedRequestId == null || suppliedRequestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : suppliedRequestId.trim();
        SecureAggregationRun existing = mapper.findRunByRequestId(requestId);
        if (existing != null) return existing;

        List<SecureAggregationProperties.Participant> participants = validateParticipants();
        List<String> participantIds = new ArrayList<>();
        for (SecureAggregationProperties.Participant participant : participants) {
            participantIds.add(participant.getId());
        }
        SecureAggregationRun run = new SecureAggregationRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setRequestId(requestId);
        run.setProtocolVersion(SecureAggregationProtocol.VERSION);
        run.setParticipantsJson(writeJson(participantIds));
        mapper.insertRun(run);

        if (!properties.isEnabled()) {
            mapper.markFailed(run.getRunId(), "secure aggregation is disabled");
            return mapper.findRun(run.getRunId());
        }
        mapper.markRunning(run.getRunId());
        try {
            Map<String, String> publicKeys = prepareAll(run.getRunId(), participantIds, participants);
            BigInteger finalValue = collectAll(run.getRunId(), participantIds, participants, publicKeys);
            if (mapper.markCompleted(run.getRunId(), finalValue.toString()) != 1) {
                throw new IllegalStateException("aggregation run changed state before completion");
            }
        } catch (Exception ex) {
            mapper.markFailed(run.getRunId(), safeFailure(ex));
        }
        return mapper.findRun(run.getRunId());
    }

    public SecureAggregationRun get(String runId) {
        SecureAggregationRun run = mapper.findRun(runId);
        if (run == null) throw new IllegalArgumentException("secure aggregation run was not found");
        return run;
    }

    public List<SecureAggregationMessageEvent> events(String runId) {
        get(runId);
        return mapper.findEvents(runId);
    }

    private Map<String, String> prepareAll(String runId, List<String> participantIds,
                                           List<SecureAggregationProperties.Participant> participants) {
        Map<String, String> publicKeys = new HashMap<>();
        for (SecureAggregationProperties.Participant participant : participants) {
            SecureAggregationWire.PrepareRequest request = new SecureAggregationWire.PrepareRequest();
            request.setRunId(runId);
            request.setParticipantIds(participantIds);
            event(runId, participant.getId(), "OUTBOUND", "PREPARE_REQUEST", "SENT",
                    jsonSize(request), null);
            try {
                SecureAggregationWire.PrepareResponse response = workerClient.prepare(participant, request);
                validateResponse(response.getRunId(), response.getParticipantId(),
                        response.getProtocolVersion(), runId, participant.getId());
                if (response.getPublicKey() == null || response.getPublicKey().isEmpty()) {
                    throw new IllegalStateException("participant returned no public key");
                }
                SecureAggregationProtocol.decodePublicKey(response.getPublicKey());
                publicKeys.put(participant.getId(), response.getPublicKey());
                event(runId, participant.getId(), "INBOUND", "PUBLIC_KEY", "RECEIVED",
                        jsonSize(response), null);
            } catch (Exception ex) {
                event(runId, participant.getId(), "INBOUND", "PUBLIC_KEY", "FAILED",
                        null, "PARTICIPANT_PREPARE_FAILED");
                throw ex;
            }
        }
        if (publicKeys.size() != 3) throw new IllegalStateException("all three public keys are required");
        return publicKeys;
    }

    private BigInteger collectAll(String runId, List<String> participantIds,
                                  List<SecureAggregationProperties.Participant> participants,
                                  Map<String, String> publicKeys) {
        BigInteger total = BigInteger.ZERO;
        Set<String> responded = new HashSet<>();
        for (SecureAggregationProperties.Participant participant : participants) {
            SecureAggregationWire.ContributionRequest request =
                    new SecureAggregationWire.ContributionRequest();
            request.setRunId(runId);
            request.setParticipantIds(participantIds);
            request.setPublicKeys(publicKeys);
            event(runId, participant.getId(), "OUTBOUND", "PUBLIC_KEY_SET", "SENT",
                    jsonSize(request), null);
            try {
                SecureAggregationWire.ContributionResponse response =
                        workerClient.contribute(participant, request);
                validateResponse(response.getRunId(), response.getParticipantId(),
                        response.getProtocolVersion(), runId, participant.getId());
                BigInteger masked = new BigInteger(response.getMaskedValue());
                if (masked.signum() < 0 || masked.compareTo(SecureAggregationProtocol.MODULUS) >= 0) {
                    throw new IllegalStateException("participant returned an out-of-range contribution");
                }
                if (!responded.add(participant.getId())) {
                    throw new IllegalStateException("participant contributed more than once");
                }
                total = total.add(masked).mod(SecureAggregationProtocol.MODULUS);
                event(runId, participant.getId(), "INBOUND", "MASKED_CONTRIBUTION", "RECEIVED",
                        jsonSize(response), null);
            } catch (Exception ex) {
                event(runId, participant.getId(), "INBOUND", "MASKED_CONTRIBUTION", "FAILED",
                        null, "PARTICIPANT_CONTRIBUTION_FAILED");
                throw ex;
            }
        }
        if (responded.size() != 3) throw new IllegalStateException("all three contributions are required");
        return total;
    }

    private List<SecureAggregationProperties.Participant> validateParticipants() {
        List<SecureAggregationProperties.Participant> configured = properties.getParticipants();
        if (configured == null || configured.size() != 3) {
            throw new IllegalStateException("secure aggregation requires exactly A, B, and C");
        }
        Set<String> ids = new HashSet<>();
        for (SecureAggregationProperties.Participant participant : configured) {
            if (participant == null || participant.getId() == null
                    || participant.getBaseUrl() == null || participant.getAuthSecret() == null) {
                throw new IllegalStateException("secure aggregation participant configuration is incomplete");
            }
            ids.add(participant.getId());
        }
        if (!ids.equals(new HashSet<>(java.util.Arrays.asList("A", "B", "C")))) {
            throw new IllegalStateException("secure aggregation participant ids must be A, B, and C");
        }
        return configured;
    }

    private void validateResponse(String responseRunId, String responseParticipant,
                                  String version, String runId, String participantId) {
        if (!runId.equals(responseRunId) || !participantId.equals(responseParticipant)
                || !SecureAggregationProtocol.VERSION.equals(version)) {
            throw new IllegalStateException("participant returned mismatched run metadata");
        }
    }

    private void event(String runId, String participantId, String direction, String type,
                       String status, Integer bytes, String errorCode) {
        SecureAggregationMessageEvent event = new SecureAggregationMessageEvent();
        event.setRunId(runId);
        event.setParticipantId(participantId);
        event.setDirection(direction);
        event.setMessageType(type);
        event.setStatus(status);
        event.setPayloadBytes(bytes);
        event.setErrorCode(errorCode);
        mapper.insertEvent(event);
    }

    private int jsonSize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (Exception ex) {
            return value.toString().getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to serialize participant metadata", ex);
        }
    }

    private String safeFailure(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) message = ex.getClass().getSimpleName();
        message = message.replaceAll("[\\r\\n\\t]", " ");
        return message.length() > 512 ? message.substring(0, 512) : message;
    }
}
