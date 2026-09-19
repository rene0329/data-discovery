package org.example.security.aggregation.worker;

import org.example.security.aggregation.SecureAggregationProtocol;
import org.example.security.aggregation.SecureAggregationWire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SecureAggregationWorkerService {
    private static final Set<String> REQUIRED_PARTICIPANTS =
            new HashSet<>(Arrays.asList("A", "B", "C"));

    private final SecureAggregationWorkerProperties properties;
    private final String participantId;
    private final Path inputFile;
    private final Map<String, EphemeralRun> runs = new ConcurrentHashMap<>();

    @Autowired
    public SecureAggregationWorkerService(
            SecureAggregationWorkerProperties properties,
            @Value("${node.name:unknown}") String nodeName,
            @Value("${file.discovery.data-directory:/dataset}") String dataDirectory) {
        this.properties = properties;
        this.participantId = resolveParticipantId(properties.getParticipantId(), nodeName);
        this.inputFile = properties.getInputFile() == null || properties.getInputFile().trim().isEmpty()
                ? Paths.get(dataDirectory).resolve("secure-aggregation/input.txt").normalize()
                : Paths.get(properties.getInputFile()).toAbsolutePath().normalize();
    }

    SecureAggregationWorkerService(SecureAggregationWorkerProperties properties,
                                   String participantId, Path inputFile) {
        this.properties = properties;
        this.participantId = participantId;
        this.inputFile = inputFile;
    }

    public String participantId() {
        return participantId;
    }

    public boolean authenticate(String phase, String runId, String providedTag) {
        if (!properties.isEnabled()) return false;
        String expected = SecureAggregationProtocol.workerAuthTag(
                properties.getAuthSecret(), phase, runId, participantId);
        return SecureAggregationProtocol.constantTimeEquals(expected, providedTag);
    }

    public SecureAggregationWire.PrepareResponse prepare(String runId,
                                                          SecureAggregationWire.PrepareRequest request) {
        validateRun(runId, request == null ? null : request.getRunId(),
                request == null ? null : request.getParticipantIds());
        removeExpiredRuns();
        EphemeralRun newRun = new EphemeralRun(
                SecureAggregationProtocol.generateEphemeralKeyPair(), Instant.now().getEpochSecond());
        EphemeralRun state = runs.putIfAbsent(runId, newRun);
        if (state == null) state = newRun;

        SecureAggregationWire.PrepareResponse response = new SecureAggregationWire.PrepareResponse();
        response.setRunId(runId);
        response.setParticipantId(participantId);
        response.setPublicKey(SecureAggregationProtocol.encodePublicKey(state.keyPair.getPublic()));
        response.setProtocolVersion(SecureAggregationProtocol.VERSION);
        return response;
    }

    public SecureAggregationWire.ContributionResponse contribute(
            String runId, SecureAggregationWire.ContributionRequest request) {
        validateRun(runId, request == null ? null : request.getRunId(),
                request == null ? null : request.getParticipantIds());
        EphemeralRun state = runs.get(runId);
        if (state == null) throw new IllegalStateException("run is unknown, expired, or already contributed");
        try {
            Map<String, String> publicKeys = request.getPublicKeys();
            if (publicKeys == null || !publicKeys.keySet().equals(REQUIRED_PARTICIPANTS)) {
                throw new IllegalArgumentException("exactly the A, B, and C public keys are required");
            }
            String ownEncoded = SecureAggregationProtocol.encodePublicKey(state.keyPair.getPublic());
            if (!SecureAggregationProtocol.constantTimeEquals(ownEncoded, publicKeys.get(participantId))) {
                throw new IllegalArgumentException("coordinator returned a different local public key");
            }

            BigInteger masked = readLocalSum();
            for (String peerId : REQUIRED_PARTICIPANTS) {
                if (peerId.equals(participantId)) continue;
                PublicKey peerPublicKey = SecureAggregationProtocol.decodePublicKey(publicKeys.get(peerId));
                BigInteger mask = SecureAggregationProtocol.derivePairwiseMask(
                        state.keyPair.getPrivate(), peerPublicKey, runId, participantId, peerId);
                masked = participantId.compareTo(peerId) < 0
                        ? masked.add(mask) : masked.subtract(mask);
            }
            masked = masked.mod(SecureAggregationProtocol.MODULUS);
            SecureAggregationWire.ContributionResponse response =
                    new SecureAggregationWire.ContributionResponse();
            response.setRunId(runId);
            response.setParticipantId(participantId);
            response.setMaskedValue(masked.toString());
            response.setProtocolVersion(SecureAggregationProtocol.VERSION);
            return response;
        } finally {
            // A contribution is one-shot. Removing the only private-key reference also
            // prevents a retry from reusing masks in a later protocol attempt.
            runs.remove(runId, state);
        }
    }

    private BigInteger readLocalSum() {
        if (!Files.isRegularFile(inputFile) || !Files.isReadable(inputFile)) {
            throw new IllegalStateException("configured local aggregation input is unavailable");
        }
        try {
            BigInteger sum = BigInteger.ZERO;
            List<String> lines = Files.readAllLines(inputFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                String withoutComment = line.split("#", 2)[0].trim();
                if (withoutComment.isEmpty()) continue;
                for (String token : withoutComment.split("[,\\s]+")) {
                    if (token.isEmpty()) continue;
                    BigInteger value = new BigInteger(token);
                    if (value.signum() < 0) {
                        throw new IllegalArgumentException("local aggregation values must be non-negative");
                    }
                    sum = sum.add(value);
                    if (sum.compareTo(SecureAggregationProtocol.MODULUS) >= 0) {
                        throw new IllegalArgumentException("local aggregation sum exceeds protocol range");
                    }
                }
            }
            return sum;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("unable to read local aggregation input", ex);
        }
    }

    private void validateRun(String pathRunId, String bodyRunId, List<String> participants) {
        if (pathRunId == null || pathRunId.trim().isEmpty() || !pathRunId.equals(bodyRunId)) {
            throw new IllegalArgumentException("run id does not match the request path");
        }
        if (participants == null || participants.size() != 3
                || !new HashSet<>(participants).equals(REQUIRED_PARTICIPANTS)
                || !REQUIRED_PARTICIPANTS.contains(participantId)) {
            throw new IllegalArgumentException("participant set must be exactly A, B, and C");
        }
    }

    private void removeExpiredRuns() {
        long cutoff = Instant.now().getEpochSecond() - Math.max(10L, properties.getRunTtlSeconds());
        runs.entrySet().removeIf(entry -> entry.getValue().createdAtEpochSeconds < cutoff);
    }

    private String resolveParticipantId(String configured, String nodeName) {
        if (configured != null && !configured.trim().isEmpty()) return configured.trim();
        if ("master-88".equals(nodeName)) return "A";
        if ("master-89".equals(nodeName)) return "B";
        if ("master-90".equals(nodeName)) return "C";
        // The discovery DaemonSet also runs on non-participant storage nodes.
        // They must remain healthy, while validateRun keeps their worker endpoint
        // outside the fixed A/B/C protocol.
        return "NON_PARTICIPANT:" + nodeName;
    }

    private static class EphemeralRun {
        private final KeyPair keyPair;
        private final long createdAtEpochSeconds;

        private EphemeralRun(KeyPair keyPair, long createdAtEpochSeconds) {
            this.keyPair = keyPair;
            this.createdAtEpochSeconds = createdAtEpochSeconds;
        }
    }
}
