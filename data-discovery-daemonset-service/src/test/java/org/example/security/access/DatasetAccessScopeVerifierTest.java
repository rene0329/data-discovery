package org.example.security.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.service.DatasetReplicaAvailabilityService;
import org.example.service.ReplicaAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatasetAccessScopeVerifierTest {
    private ObjectMapper objectMapper;
    private NodeAccessTokenProperties properties;
    private DatasetAccessScopeVerifier verifier;
    private DatasetRegistrationMapper datasets;
    private NodeManagementMapper nodes;
    private DatasetReplicaAvailabilityService availability;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new NodeAccessTokenProperties();
        properties.setHmacSecret("a-test-secret-long-enough-for-hmac");
        datasets = mock(DatasetRegistrationMapper.class);
        nodes = mock(NodeManagementMapper.class);
        availability = mock(DatasetReplicaAvailabilityService.class);
        verifier = new DatasetAccessScopeVerifier(
                objectMapper, properties, "master-88", datasets, nodes, availability);
        registerUsableReplica(1L, "v1", "/dataset/a.bin", 88);
    }

    @Test
    void acceptsOnlyExactDatasetVersionPathActionAndNode() throws Exception {
        String token = token("dataset-1", "v1", "/dataset/a.bin", "READ", "master-88",
                Instant.now().plusSeconds(60).getEpochSecond());

        NodeAccessTokenClaims claims = verifier.verify("Bearer " + token,
                "dataset-1", "v1", "/dataset/a.bin", "READ");

        assertEquals("reviewer-a", claims.getSubject());
        assertThrows(DatasetAccessScopeVerifier.TokenVerificationException.class,
                () -> verifier.verify("Bearer " + token,
                        "dataset-1", "v1", "/dataset/other.bin", "READ"));
    }

    @Test
    void rejectsExpiredAndForgedTokens() throws Exception {
        String expired = token("dataset-1", "v1", "/dataset/a.bin", "READ", "master-88",
                Instant.now().minusSeconds(1).getEpochSecond());
        assertEquals("TOKEN_EXPIRED", assertThrows(
                DatasetAccessScopeVerifier.TokenVerificationException.class,
                () -> verifier.verifyPathAction(expired, "/dataset/a.bin", "READ"))
                .getErrorCode());

        String valid = token("dataset-1", "v1", "/dataset/a.bin", "READ", "master-88",
                Instant.now().plusSeconds(60).getEpochSecond());
        String forged = valid.substring(0, valid.length() - 1)
                + (valid.endsWith("A") ? "B" : "A");
        assertEquals("TOKEN_SIGNATURE_INVALID", assertThrows(
                DatasetAccessScopeVerifier.TokenVerificationException.class,
                () -> verifier.verifyPathAction(forged, "/dataset/a.bin", "READ"))
                .getErrorCode());
    }

    @Test
    void directReadRejectsDatasetIdAndReplicaPathSubstitution() throws Exception {
        String token = token("1", "v1", "/dataset/b.bin", "READ", "master-88",
                Instant.now().plusSeconds(60).getEpochSecond());

        DatasetAccessScopeVerifier.TokenVerificationException denied = assertThrows(
                DatasetAccessScopeVerifier.TokenVerificationException.class,
                () -> verifier.verifyPathAction(token, "/dataset/b.bin", "READ"));

        assertEquals("TOKEN_REGISTRY_SCOPE_MISMATCH", denied.getErrorCode());
    }

    @Test
    void directReadRejectsReplicaThatFailedStrongVerification() throws Exception {
        when(availability.evaluate(org.mockito.ArgumentMatchers.any(DatasetReplica.class)))
                .thenReturn(new ReplicaAvailability("VERIFY_FAILED", false, "digest mismatch"));
        String token = token("1", "v1", "/dataset/a.bin", "READ", "master-88",
                Instant.now().plusSeconds(60).getEpochSecond());

        DatasetAccessScopeVerifier.TokenVerificationException denied = assertThrows(
                DatasetAccessScopeVerifier.TokenVerificationException.class,
                () -> verifier.verifyPathAction(token, "/dataset/a.bin", "READ"));

        assertEquals("REPLICA_NOT_USABLE", denied.getErrorCode());
    }

    @Test
    void concurrentSingleUseTokenAllowsExactlyOneVerification() throws Exception {
        String token = token("1", "v1", "/dataset/a.bin", "READ", "master-88",
                Instant.now().plusSeconds(60).getEpochSecond(), true);
        int workers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        ready.countDown();
                        start.await();
                        try {
                            verifier.verifyPathAction("Bearer " + token, "/dataset/a.bin", "READ");
                            return "ALLOWED";
                        } catch (DatasetAccessScopeVerifier.TokenVerificationException ex) {
                            return ex.getErrorCode();
                        }
                    }
                }));
            }
            ready.await();
            start.countDown();
            int allowed = 0;
            int replayed = 0;
            for (Future<String> future : futures) {
                String result = future.get();
                if ("ALLOWED".equals(result)) allowed++;
                if ("TOKEN_REPLAYED".equals(result)) replayed++;
            }
            assertEquals(1, allowed);
            assertEquals(workers - 1, replayed);
        } finally {
            pool.shutdownNow();
        }
    }

    private String token(String datasetId, String version, String path, String action,
                         String target, long expiry) throws Exception {
        return token(datasetId, version, path, action, target, expiry, false);
    }

    private String token(String datasetId, String version, String path, String action,
                         String target, long expiry, boolean singleUse) throws Exception {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("subject", "reviewer-a");
        claims.put("datasetId", datasetId);
        claims.put("datasetVersion", version);
        claims.put("path", path);
        claims.put("action", action);
        claims.put("targetNode", target);
        claims.put("issuedAtEpochSeconds", Instant.now().getEpochSecond());
        claims.put("expiresAtEpochSeconds", expiry);
        claims.put("jti", "test-jti");
        claims.put("singleUse", singleUse);
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                objectMapper.writeValueAsBytes(claims));
        String signed = "t4v1." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getHmacSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        return signed + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
    }

    private void registerUsableReplica(Long datasetId, String version, String path, int nodeId) {
        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(datasetId)
                .datasetVersion(version).status("ACTIVE").build();
        NodeManagement node = NodeManagement.builder().nodeId(nodeId).nodeName("master-88").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(10L).datasetId(datasetId)
                .nodeId(nodeId).filePath(path).availability("AVAILABLE").build();
        when(datasets.findDatasetById(datasetId)).thenReturn(dataset);
        when(nodes.getNodeByName("master-88")).thenReturn(node);
        when(datasets.findReplicaByDatasetNodePath(datasetId, nodeId, path)).thenReturn(replica);
        when(availability.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
    }
}
