package org.example.security.aggregation.coordinator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.security.aggregation.SecureAggregationProtocol;
import org.example.security.aggregation.SecureAggregationWire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecureAggregationCoordinatorServiceTest {
    private SecureAggregationProperties properties;
    private SecureAggregationWorkerClient client;
    private SecureAggregationMapper mapper;
    private AtomicReference<SecureAggregationRun> stored;

    @BeforeEach
    void setUp() {
        properties = new SecureAggregationProperties();
        client = mock(SecureAggregationWorkerClient.class);
        mapper = mock(SecureAggregationMapper.class);
        stored = new AtomicReference<>();
        when(mapper.findRunByRequestId(anyString())).thenReturn(null);
        when(mapper.insertRun(any())).thenAnswer(invocation -> {
            SecureAggregationRun run = invocation.getArgument(0);
            run.setStatus("PENDING");
            stored.set(run);
            return 1;
        });
        when(mapper.markRunning(anyString())).thenAnswer(invocation -> {
            stored.get().setStatus("RUNNING");
            return 1;
        });
        when(mapper.markCompleted(anyString(), anyString())).thenAnswer(invocation -> {
            stored.get().setStatus("COMPLETED");
            stored.get().setFinalValue(invocation.getArgument(1));
            stored.get().setFailureReason(null);
            return 1;
        });
        when(mapper.markFailed(anyString(), anyString())).thenAnswer(invocation -> {
            stored.get().setStatus("FAILED");
            stored.get().setFinalValue(null);
            stored.get().setFailureReason(invocation.getArgument(1));
            return 1;
        });
        when(mapper.findRun(anyString())).thenAnswer(invocation -> stored.get());
        when(mapper.insertEvent(any())).thenReturn(1);
    }

    @Test
    void completesOnlyAfterAllThreeMaskedContributions() {
        Map<String, String> keys = new HashMap<>();
        for (SecureAggregationProperties.Participant participant : properties.getParticipants()) {
            keys.put(participant.getId(), SecureAggregationProtocol.encodePublicKey(
                    SecureAggregationProtocol.generateEphemeralKeyPair().getPublic()));
        }
        when(client.prepare(any(), any())).thenAnswer(invocation -> {
            SecureAggregationProperties.Participant participant = invocation.getArgument(0);
            SecureAggregationWire.PrepareRequest request = invocation.getArgument(1);
            SecureAggregationWire.PrepareResponse response = new SecureAggregationWire.PrepareResponse();
            response.setRunId(request.getRunId());
            response.setParticipantId(participant.getId());
            response.setProtocolVersion(SecureAggregationProtocol.VERSION);
            response.setPublicKey(keys.get(participant.getId()));
            return response;
        });
        when(client.contribute(any(), any())).thenAnswer(invocation -> {
            SecureAggregationProperties.Participant participant = invocation.getArgument(0);
            SecureAggregationWire.ContributionRequest request = invocation.getArgument(1);
            SecureAggregationWire.ContributionResponse response =
                    new SecureAggregationWire.ContributionResponse();
            response.setRunId(request.getRunId());
            response.setParticipantId(participant.getId());
            response.setProtocolVersion(SecureAggregationProtocol.VERSION);
            response.setMaskedValue(String.valueOf(5 + participant.getId().charAt(0) - 'A'));
            return response;
        });
        SecureAggregationCoordinatorService service = new SecureAggregationCoordinatorService(
                properties, client, mapper, new ObjectMapper());

        SecureAggregationRun result = service.start("request-1");

        assertEquals("COMPLETED", result.getStatus());
        assertEquals("18", result.getFinalValue());
        assertNull(result.getFailureReason());
    }

    @Test
    void participantFailureFailsWholeRunWithoutPartialResult() {
        when(client.prepare(any(), any())).thenThrow(new IllegalStateException("worker timeout"));
        SecureAggregationCoordinatorService service = new SecureAggregationCoordinatorService(
                properties, client, mapper, new ObjectMapper());

        SecureAggregationRun result = service.start("request-2");

        assertEquals("FAILED", result.getStatus());
        assertNull(result.getFinalValue());
        assertEquals("worker timeout", result.getFailureReason());
    }
}
