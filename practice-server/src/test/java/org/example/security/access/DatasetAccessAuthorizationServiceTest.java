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
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class DatasetAccessAuthorizationServiceTest {
    private DatasetAccessAuditMapper audits;
    private DatasetAccessAuthorizationService service;
    private DatasetAccessTokenCodec codec;
    private DatasetRegistrationMapper datasets;
    private NodeManagementMapper nodes;
    private DatasetReplicaAvailabilityService availability;

    @BeforeEach
    void setUp() {
        DatasetAccessProperties properties = new DatasetAccessProperties();
        audits = mock(DatasetAccessAuditMapper.class);
        datasets = mock(DatasetRegistrationMapper.class);
        nodes = mock(NodeManagementMapper.class);
        availability = mock(DatasetReplicaAvailabilityService.class);
        codec = new DatasetAccessTokenCodec(new ObjectMapper(), properties);
        service = new DatasetAccessAuthorizationService(
                properties, codec, audits, datasets, nodes, availability);
        registerUsableReplica(1L, "v1", "/dataset/a.bin", "master-88", 88);
    }

    @Test
    void authenticatedAllowedPrincipalGetsExactScopeToken() {
        AccessScope scope = scope("1", "/dataset/a.bin", "READ", "master-88");

        AccessAuthorizationResult result = service.authorizeAndIssue(
                basic("reviewer-a", "topic4-reviewer-a-secret"), scope, context());
        AccessTokenClaims claims = service.verifyAndAudit(result.getToken(), scope, context());

        assertEquals("reviewer-a", claims.getSubject());
        assertEquals("1", claims.getDatasetId());
        assertNotNull(claims.getJti());
        verify(audits, org.mockito.Mockito.times(2)).insert(any(DatasetAccessAuditEvent.class));
    }

    @Test
    void identityComesFromCredentialsAndUnauthorizedActionIsDenied() {
        AccessAuthorizationException denied = assertThrows(AccessAuthorizationException.class,
                () -> service.authorizeAndIssue(
                        basic("reviewer-b", "topic4-reviewer-b-secret"),
                        scope("1", "/dataset/a.bin", "READ", "master-88"), context()));

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
        assertEquals("ACCESS_DENIED", denied.getErrorCode());
        verify(audits).insert(any(DatasetAccessAuditEvent.class));
    }

    @Test
    void validTokenCannotBeReusedForAnotherPathOrTarget() {
        AccessScope issued = scope("1", "/dataset/a.bin", "READ", "master-88");
        String token = service.authorizeAndIssue(
                basic("reviewer-a", "topic4-reviewer-a-secret"), issued, context()).getToken();

        AccessAuthorizationException denied = assertThrows(AccessAuthorizationException.class,
                () -> service.verifyAndAudit(token,
                        scope("1", "/dataset/b.bin", "READ", "master-89"), context()));

        assertEquals("TOKEN_SCOPE_MISMATCH", denied.getErrorCode());
    }

    @Test
    void expiredSignedTokenIsDeniedAndAudited() {
        AccessTokenClaims claims = new AccessTokenClaims();
        claims.setSubject("reviewer-a");
        claims.setDatasetId("dataset-1");
        claims.setDatasetVersion("v1");
        claims.setPath("/dataset/a.bin");
        claims.setAction("READ");
        claims.setTargetNode("master-88");
        claims.setIssuedAtEpochSeconds(Instant.now().minusSeconds(20).getEpochSecond());
        claims.setExpiresAtEpochSeconds(Instant.now().minusSeconds(10).getEpochSecond());
        claims.setJti("expired-test-token");

        AccessAuthorizationException denied = assertThrows(AccessAuthorizationException.class,
                () -> service.verifyAndAudit(codec.encode(claims),
                        scope("dataset-1", "/dataset/a.bin", "READ", "master-88"), context()));

        assertEquals("TOKEN_EXPIRED", denied.getErrorCode());
        verify(audits).insert(any(DatasetAccessAuditEvent.class));
    }

    @Test
    void internalTokensUseSameFormatAndAreAudited() {
        AccessScope scope = scope("dataset-2", "/dataset/copy.bin", "COPY", "master-89");
        AccessAuthorizationResult result = service.issueInternal(scope, context());

        assertEquals("SYSTEM", codec.decodeAndVerify(result.getToken()).getSubject());
        verify(audits).insert(any(DatasetAccessAuditEvent.class));
    }

    @Test
    void privacyInternalTokenCarriesSingleUseClaim() {
        AccessScope scope = scope("1", "/dataset/a.bin", "READ", "master-88");

        AccessAuthorizationResult result = service.issueInternalOneTime(scope, context());

        AccessTokenClaims claims = codec.decodeAndVerify(result.getToken());
        assertEquals("SYSTEM", claims.getSubject());
        org.junit.jupiter.api.Assertions.assertTrue(claims.isSingleUse());
    }

    @Test
    void publicTokenCannotSubstituteAnotherDatasetsPath() {
        AccessAuthorizationException denied = assertThrows(AccessAuthorizationException.class,
                () -> service.authorizeAndIssue(
                        basic("reviewer-a", "topic4-reviewer-a-secret"),
                        scope("1", "/dataset/b.bin", "READ", "master-88"), context()));

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
        assertEquals("SCOPE_NOT_REGISTERED", denied.getErrorCode());
    }

    @Test
    void publicReadTokenIsDeniedForUnusableReplica() {
        when(availability.evaluate(any(DatasetReplica.class)))
                .thenReturn(new ReplicaAvailability("VERIFY_FAILED", false, "digest mismatch"));

        AccessAuthorizationException denied = assertThrows(AccessAuthorizationException.class,
                () -> service.authorizeAndIssue(
                        basic("reviewer-a", "topic4-reviewer-a-secret"),
                        scope("1", "/dataset/a.bin", "READ", "master-88"), context()));

        assertEquals(HttpStatus.CONFLICT, denied.getStatus());
        assertEquals("REPLICA_NOT_USABLE", denied.getErrorCode());
    }

    @Test
    void publicIdentityCannotReceiveMutationToken() {
        AccessAuthorizationException denied = assertThrows(AccessAuthorizationException.class,
                () -> service.authorizeAndIssue(
                        basic("reviewer-a", "topic4-reviewer-a-secret"),
                        scope("1", "/dataset/a.bin", "DELETE", "master-88"), context()));

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
        assertEquals("PUBLIC_ACTION_NOT_ALLOWED", denied.getErrorCode());
    }

    @Test
    void auditEventDecisionFilterIsNormalizedAndOptional() {
        service.findAuditEvents(" ", null, "owner-a", " denied ", 1000);
        verify(audits).find(null, null, "owner-a", "DENIED", 500);

        service.findAuditEvents(null, "run-1", null, 0);
        verify(audits).find(null, "run-1", null, null, 1);
    }

    private AccessScope scope(String dataset, String path, String action, String target) {
        return new AccessScope(dataset, "v1", path, action, target);
    }

    private AccessAuditContext context() {
        return new AccessAuditContext("request-1", "run-1", "127.0.0.1");
    }

    private String basic(String principal, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (principal + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private void registerUsableReplica(Long datasetId, String version, String path,
                                       String nodeName, int nodeId) {
        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(datasetId)
                .datasetVersion(version).status("ACTIVE").build();
        NodeManagement node = NodeManagement.builder().nodeId(nodeId).nodeName(nodeName).build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(10L).datasetId(datasetId)
                .nodeId(nodeId).filePath(path).availability("AVAILABLE").build();
        when(datasets.findDatasetById(datasetId)).thenReturn(dataset);
        when(nodes.getNodeByName(nodeName)).thenReturn(node);
        when(datasets.findReplicaByDatasetNodePath(datasetId, nodeId, path)).thenReturn(replica);
        when(availability.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
    }
}
