package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PrivacyComputeServiceEvidenceTest {
    private static final String DIGEST = "sha256:"
            + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private PrivacyComputeService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new PrivacyComputeService(mock(PrivacyComputeMapper.class),
                mock(PrivacyTemplateCatalog.class), mock(PrivacyProviderRegistry.class),
                mock(PrivacyJobSpecResolver.class), mock(PrivacyPartyAuthenticator.class),
                mock(PrivacyInputStagingService.class), objectMapper, Runnable::run);
    }

    @Test
    void preservesGatewayAndMpSpdzProtocolCountersIncludingUnreportedNull() {
        Map<String, Object> report = messageReport("MP-SPDZ process-reported byte counters",
                1500000L, Collections.singletonList(map("counter", "Data sent", "bytes", 1500000L)));
        Map<String, Object> unreported = messageReport("MP-SPDZ process-reported byte counters",
                null, Collections.emptyList());
        Map<String, Object> adapter = map(
                "protocolExecutable", "malicious-rep-ring-party.x",
                "protocol", "malicious replicated ring", "program", "topic4_sum",
                "recipientMask", 1, "programScheduleDigest", DIGEST, "localInputDigest", DIGEST,
                "localPartyIndex", 0, "tlsEnabled", true,
                "tlsCertificateDigests", map("P0.pem", DIGEST, "P1.pem", DIGEST, "P2.pem", DIGEST),
                "engineLogDigest", DIGEST, "protocolMessages", report, "plaintextFallback", false);
        Map<String, Object> gateway = map(
                "contractVersion", "topic4.privacy.evidence/v1", "providerId", "MP_SPDZ",
                "engineVersion", "0.4.3", "imageDigest", DIGEST, "requestDigest", DIGEST,
                "participants", Arrays.asList("A", "B", "C"), "resultRecipients", Collections.singletonList("A"),
                "engineEvidence", map(
                        "parties", map("A", map("engineEvidence", adapter),
                                "B", map("engineEvidence", map("protocolMessages", unreported))),
                        "allPartiesRequired", true,
                        "protocolMessages", map("parties", map("A", report, "B", unreported),
                                "reportedBytes", 1500000L, "summaryDigest", DIGEST)),
                "plaintextFallback", false);

        Map<String, Object> sanitized = service.sanitizeProviderEvidence(gateway);

        assertEquals(gateway, sanitized);
        Map<?, ?> summary = (Map<?, ?>) ((Map<?, ?>) sanitized.get("engineEvidence")).get("protocolMessages");
        Map<?, ?> missing = (Map<?, ?>) ((Map<?, ?>) summary.get("parties")).get("B");
        assertTrue(missing.containsKey("reportedBytes"));
        assertEquals(null, missing.get("reportedBytes"));
    }

    @Test
    void preservesPsiProtocolCountersAndLauncherAndTransportEvidence() {
        Map<String, Object> adapter = map(
                "protocol", "APSI", "launcherRevision", "72f3312fd9142ea567f3a170d0808a2118b75af8",
                "localInputDigest", DIGEST, "localPartyRank", 1, "launchConfigDigest", DIGEST,
                "engineLogDigest", DIGEST,
                "protocolMessages", messageReport("SecretFlow PSI launcher Report byte counters", 1536L,
                        Arrays.asList(map("counter", "network.sent_bytes", "bytes", 1024L),
                                map("counter", "network.recv_bytes", "bytes", 512L))),
                "launcherSilent", true,
                "serverQueryKeyLogCheck", "launcher configured silent; acceptance must scan the SERVER log against CLIENT queries",
                "resultDigest", DIGEST, "knownDisclosure", "receiver may learn pairwise intersection cardinality",
                "kusciaContextDigest", DIGEST, "kusciaDeploymentId", "psi-parties",
                "transportSecurity", "Kuscia Cluster endpoint routed by Envoy under domain MTLS",
                "plaintextFallback", false);

        assertEquals(adapter, service.sanitizeProviderEvidence(adapter));
    }

    @Test
    void preservesSecretFlowHeAndVflEvidenceWithoutRequiringRawResults() {
        Map<String, Object> he = map(
                "framework", "SecretFlow", "frameworkVersion", "1.11.0b1", "sourceRevision", "fixed-revision",
                "device", "HEU/PHEU", "scheme", "Paillier", "keySizeBits", 2048,
                "keyKeeper", "A", "ciphertextEvaluator", "B", "operation", "sum", "fixedPointScale", 1000,
                "freshKeyPerAttempt", true, "kusciaContextDigest", DIGEST, "kusciaDeploymentId", "he-parties",
                "transport", "KUSCIA_CLUSTER_MTLS_ENVOY", "partyId", "A", "localInputDigest", DIGEST,
                "resultRecipients", Collections.singletonList("A"), "released", true, "plaintextFallback", false);
        Map<String, Object> vfl = map(
                "framework", "SecretFlow", "frameworkVersion", "1.11.0b1", "sourceRevision", "fixed-revision",
                "alignmentProtocol", "RR22_FAST_PSI_2PC", "intersectionCount", 10, "trainer", "SecureBoost",
                "secureDevice", "HEU/PHEU", "scheme", "Paillier", "keySizeBits", 2048, "labelHolder", "A",
                "modelShardsStayAtOwner", true, "freshKeyPerAttempt", true, "kusciaContextDigest", DIGEST,
                "kusciaDeploymentId", "vfl-parties", "transport", "KUSCIA_CLUSTER_MTLS_ENVOY",
                "partyId", "B", "localInputDigest", DIGEST, "resultRecipients", Collections.singletonList("A"),
                "released", false, "plaintextFallback", false);

        assertEquals(he, service.sanitizeProviderEvidence(he));
        assertEquals(vfl, service.sanitizeProviderEvidence(vfl));
    }

    @Test
    void preservesSflDistributedExecutionAndModelArtifactMetadata() {
        Map<String, Object> adapter = map(
                "framework", "SFL", "frameworkVersion", "1.0.0b1", "sourceRevision", "fixed-revision",
                "strategy", "fed_avg_w", "aggregator", "SPUAggregator",
                "executionMode", "KusciaDeployment SecretFlow production SPMD",
                "transport", "KUSCIA_CLUSTER_MTLS_ENVOY", "servingId", "sfl-parties", "kusciaContextDigest", DIGEST,
                "localInputDigest", DIGEST,
                "localModel", map("modelReference", "runtime://A/models/job-1/attempt-1/logreg",
                        "modelDigest", DIGEST, "modelBytes", 4096L, "modelFiles", 3),
                "plaintextFallback", false);

        assertEquals(adapter, service.sanitizeProviderEvidence(adapter));
    }

    @Test
    void recursivelyRemovesSensitiveFieldsFromGatewayPartyReportsAndAdapterMetadata() throws Exception {
        Map<String, Object> expected = map("engineEvidence", map("parties", map("A", map(
                "protocolMessages", messageReport("engine counters", 512,
                        Collections.singletonList(map("counter", "Data sent", "bytes", 512))),
                "localModel", map("modelDigest", DIGEST, "modelBytes", 128),
                "messages", Collections.singletonList(map("messageDigest", DIGEST, "payloadBytes", 64))))));
        @SuppressWarnings("unchecked") Map<String, Object> dirty = objectMapper.readValue(
                objectMapper.writeValueAsString(expected), LinkedHashMap.class);
        injectSensitiveFields(dirty);

        Map<String, Object> sanitized = service.sanitizeProviderEvidence(dirty);

        assertEquals(expected, sanitized);
        assertFalse(objectMapper.writeValueAsString(sanitized).contains("must-not-persist"));
        assertTrue(dirty.containsKey("read_token"), "sanitizing must not mutate the provider response");
    }

    @Test
    void certificateFingerprintsDoNotOpenAnArbitraryFilenameOrPayloadDictionary() {
        Map<String, Object> certificates = map(
                "P0.pem", DIGEST, "P1.pem", "-----BEGIN PRIVATE KEY-----",
                "P2.pem", map("privateKey", "must-not-persist"),
                "../../P0.pem", DIGEST, "/private/key.pem", DIGEST, "token", DIGEST);

        assertEquals(map("tlsCertificateDigests", map("P0.pem", DIGEST)),
                service.sanitizeProviderEvidence(map("tlsCertificateDigests", certificates)));
    }

    private Map<String, Object> messageReport(String source, Number bytes, List<?> observations) {
        return map("source", source, "measurementScope", "ENGINE_REPORTED_PROTOCOL_BYTES",
                "observations", observations, "reportedBytes", bytes,
                "transcriptDigestAvailable", false, "summaryDigest", DIGEST);
    }

    private void injectSensitiveFields(Object value) {
        if (value instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) value;
            for (Object child : new ArrayList<>(values.values())) injectSensitiveFields(child);
            for (String key : Arrays.asList("token", "read_token", "Authorization", "rawInput", "raw_input",
                    "privateKey", "private_key", "secret", "share", "shares", "mask", "masks", "seed",
                    "command", "commands", "argv", "script", "path", "inputPath", "outputPath", "payload")) {
                values.put(key, "must-not-persist");
            }
        } else if (value instanceof List) {
            for (Object child : (List<?>) value) injectSensitiveFields(child);
        }
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
}
