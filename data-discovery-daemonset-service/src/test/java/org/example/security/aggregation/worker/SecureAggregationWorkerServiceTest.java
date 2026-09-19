package org.example.security.aggregation.worker;

import org.example.security.aggregation.SecureAggregationProtocol;
import org.example.security.aggregation.SecureAggregationWire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureAggregationWorkerServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void threeEphemeralWorkersCancelPairwiseMasks() throws Exception {
        List<String> ids = Arrays.asList("A", "B", "C");
        Map<String, SecureAggregationWorkerService> workers = new HashMap<>();
        Map<String, String> publicKeys = new HashMap<>();
        int value = 4;
        for (String id : ids) {
            Path input = tempDir.resolve(id + ".txt");
            Files.write(input, Arrays.asList(String.valueOf(value), "1"), StandardCharsets.UTF_8);
            SecureAggregationWorkerProperties properties = new SecureAggregationWorkerProperties();
            SecureAggregationWorkerService worker =
                    new SecureAggregationWorkerService(properties, id, input);
            workers.put(id, worker);
            SecureAggregationWire.PrepareRequest prepare = new SecureAggregationWire.PrepareRequest();
            prepare.setRunId("run-1");
            prepare.setParticipantIds(ids);
            publicKeys.put(id, worker.prepare("run-1", prepare).getPublicKey());
            value += 3;
        }

        BigInteger aggregate = BigInteger.ZERO;
        for (String id : ids) {
            SecureAggregationWire.ContributionRequest request =
                    new SecureAggregationWire.ContributionRequest();
            request.setRunId("run-1");
            request.setParticipantIds(ids);
            request.setPublicKeys(publicKeys);
            BigInteger masked = new BigInteger(
                    workers.get(id).contribute("run-1", request).getMaskedValue());
            aggregate = aggregate.add(masked).mod(SecureAggregationProtocol.MODULUS);
        }

        // (4+1) + (7+1) + (10+1)
        assertEquals(new BigInteger("24"), aggregate);
    }

    @Test
    void missingParticipantAndContributionReplayAreRejected() throws Exception {
        Path input = tempDir.resolve("A.txt");
        Files.write(input, Arrays.asList("5"), StandardCharsets.UTF_8);
        SecureAggregationWorkerService worker = new SecureAggregationWorkerService(
                new SecureAggregationWorkerProperties(), "A", input);
        SecureAggregationWire.PrepareRequest invalid = new SecureAggregationWire.PrepareRequest();
        invalid.setRunId("run-2");
        invalid.setParticipantIds(Arrays.asList("A", "B"));
        assertThrows(IllegalArgumentException.class, () -> worker.prepare("run-2", invalid));

        List<String> ids = Arrays.asList("A", "B", "C");
        SecureAggregationWire.PrepareRequest prepare = new SecureAggregationWire.PrepareRequest();
        prepare.setRunId("run-3");
        prepare.setParticipantIds(ids);
        String own = worker.prepare("run-3", prepare).getPublicKey();
        Map<String, String> keys = new HashMap<>();
        keys.put("A", own);
        keys.put("B", SecureAggregationProtocol.encodePublicKey(
                SecureAggregationProtocol.generateEphemeralKeyPair().getPublic()));
        keys.put("C", SecureAggregationProtocol.encodePublicKey(
                SecureAggregationProtocol.generateEphemeralKeyPair().getPublic()));
        SecureAggregationWire.ContributionRequest contribution =
                new SecureAggregationWire.ContributionRequest();
        contribution.setRunId("run-3");
        contribution.setParticipantIds(ids);
        contribution.setPublicKeys(keys);
        worker.contribute("run-3", contribution);
        assertThrows(IllegalStateException.class, () -> worker.contribute("run-3", contribution));
    }
}
