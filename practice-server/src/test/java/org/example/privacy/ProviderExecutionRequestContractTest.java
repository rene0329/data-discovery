package org.example.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.StagingInput;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderExecutionRequestContractTest {
    @Test
    void serializesRunnerFieldsAtTopLevelWithEphemeralStaging() throws Exception {
        ProviderExecutionRequest request = new ProviderExecutionRequest();
        request.setJobId("pcj-1");
        request.setAttemptId("pca-1");
        request.setTemplateId("secure-sum-3p-v1");
        request.setSecurityProfile("MALICIOUS_3PC_HONEST_MAJORITY");
        request.setProtocolVersion("mp-spdz-0.4.3/malicious-rep-ring");
        request.setImageDigest("sha256:" + repeat('a', 64));
        request.setSpecDigest("sha256:" + repeat('b', 64));
        request.setTimeoutSeconds(1800);
        request.setResultRecipients(Collections.singletonList("A"));

        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", "value");
        column.put("type", "integer");
        request.setParticipants(new ArrayList<ParticipantSpec>());
        request.setStaging(new ArrayList<StagingInput>());
        int index = 0;
        for (String party : Arrays.asList("A", "B", "C")) {
            ParticipantSpec participant = new ParticipantSpec();
            participant.setPartyId(party);
            participant.setRole("PARTY");
            participant.setDatasetId(String.valueOf(42 + index));
            participant.setDatasetVersion("v1");
            participant.setFields(Collections.singletonList("value"));
            request.getParticipants().add(participant);

            StagingInput staging = new StagingInput();
            staging.setPartyId(party);
            staging.setDatasetId(String.valueOf(42 + index));
            staging.setDatasetVersion("v1");
            staging.setExpectedSha256("sha256:" + repeat('c', 64));
            staging.setExpectedSize(123L);
            staging.setExpectedSchema(Collections.singletonList(column));
            staging.setExpectedSchemaDigest("sha256:" + repeat('d', 64));
            staging.setNodeName("master-" + (89 + index));
            staging.setAgentBaseUrl("http://10.0.0." + (index + 1) + ":8080");
            staging.setSourcePath("/dataset/" + party.toLowerCase() + ".csv");
            staging.setTokenType("Topic4Scope");
            staging.setToken("ephemeral-token-" + party);
            staging.setExpiresAt(Instant.parse("2026-09-19T12:00:00Z"));
            request.getStaging().add(staging);
            index++;
        }

        JsonNode json = new ObjectMapper().findAndRegisterModules().readTree(
                new ObjectMapper().findAndRegisterModules().writeValueAsBytes(request));

        assertFalse(json.has("spec"));
        assertTrue(json.has("participants"));
        assertTrue(json.has("resultRecipients"));
        assertTrue(json.has("timeoutSeconds"));
        assertTrue(json.has("enginePolicy"));
        JsonNode input = json.path("staging").get(0);
        assertEquals(3, json.path("participants").size());
        assertEquals(3, json.path("staging").size());
        assertEquals("ephemeral-token-A", input.path("token").asText());
        assertEquals("/dataset/a.csv", input.path("sourcePath").asText());
        assertEquals(123L, input.path("expectedSize").asLong());
        assertEquals("sha256:" + repeat('c', 64), input.path("expectedSha256").asText());
        assertTrue(input.path("expectedSchema").isArray());
        assertEquals("value", input.path("expectedSchema").get(0).path("name").asText());
        assertEquals("sha256:" + repeat('d', 64), input.path("expectedSchemaDigest").asText());
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
