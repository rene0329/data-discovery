package org.example.security.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeDatasetAccessAuditMapper;
import org.example.mapper.NodeDatasetAccessTokenConsumptionMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.service.DatasetReplicaAvailabilityService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reusable verifier for read/download/copy/verify/write/delete node endpoints. */
@Component
public class DatasetAccessScopeVerifier {
    private static final String PREFIX = "t4v1";
    private final ObjectMapper objectMapper;
    private final NodeAccessTokenProperties properties;
    private final String nodeName;
    private final DatasetRegistrationMapper datasets;
    private final NodeManagementMapper nodes;
    private final DatasetReplicaAvailabilityService availability;
    private final ConcurrentHashMap<String, Long> consumedSingleUseJtis = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private NodeDatasetAccessAuditMapper auditMapper;

    @Autowired(required = false)
    private NodeDatasetAccessTokenConsumptionMapper tokenConsumptionMapper;

    @Autowired
    public DatasetAccessScopeVerifier(ObjectMapper objectMapper,
                                      NodeAccessTokenProperties properties,
                                      @Value("${node.name:unknown}") String nodeName,
                                      DatasetRegistrationMapper datasets,
                                      NodeManagementMapper nodes,
                                      DatasetReplicaAvailabilityService availability) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.nodeName = nodeName;
        this.datasets = datasets;
        this.nodes = nodes;
        this.availability = availability;
    }

    public NodeAccessTokenClaims verify(String authorizationHeader, String datasetId,
                                        String datasetVersion, String path, String action) {
        NodeAccessTokenClaims claims = null;
        try {
            claims = decode(authorizationHeader);
            String normalizedPath = normalizePath(path);
            if (!same(claims.getDatasetId(), required(datasetId, "datasetId"))
                    || !same(claims.getDatasetVersion(), required(datasetVersion, "datasetVersion"))
                    || !same(normalizePath(claims.getPath()), normalizedPath)
                    || !same(claims.getAction(), required(action, "action").toUpperCase())
                    || !same(claims.getTargetNode(), nodeName)) {
                throw new TokenVerificationException("TOKEN_SCOPE_MISMATCH",
                        "token does not permit this node dataset operation");
            }
            consumeIfSingleUse(claims);
            audit(claims, datasetId, datasetVersion, path, action, "ALLOWED", "TOKEN_VERIFIED");
            return claims;
        } catch (TokenVerificationException ex) {
            audit(claims, datasetId, datasetVersion, path, action, "DENIED", ex.getErrorCode());
            throw ex;
        }
    }

    public NodeAccessTokenClaims verifyPathAction(String authorizationHeader, String path,
                                                  String action) {
        NodeAccessTokenClaims claims = null;
        try {
            claims = decode(authorizationHeader);
            if (!same(normalizePath(claims.getPath()), normalizePath(path))
                    || !same(claims.getAction(), required(action, "action").toUpperCase())
                    || !same(claims.getTargetNode(), nodeName)) {
                throw new TokenVerificationException("TOKEN_SCOPE_MISMATCH",
                        "token does not permit this node path operation");
            }
            validateRegisteredPathScope(claims, path, action);
            consumeIfSingleUse(claims);
            audit(claims, claims.getDatasetId(), claims.getDatasetVersion(), path, action,
                    "ALLOWED", "TOKEN_VERIFIED");
            return claims;
        } catch (TokenVerificationException ex) {
            audit(claims, claims == null ? null : claims.getDatasetId(),
                    claims == null ? null : claims.getDatasetVersion(), path, action,
                    "DENIED", ex.getErrorCode());
            throw ex;
        }
    }

    private void validateRegisteredPathScope(NodeAccessTokenClaims claims, String path,
                                             String requestedAction) {
        String action = required(requestedAction, "action").toUpperCase();
        Long datasetId = parseDatasetId(claims.getDatasetId());
        boolean system = "SYSTEM".equals(claims.getSubject());
        if (datasetId == null) {
            // Compatibility for trusted control-plane operations on legacy or
            // not-yet-registered paths. Public subjects can never use it.
            if (system) return;
            throw new TokenVerificationException("TOKEN_REGISTRY_SCOPE_MISMATCH",
                    "token dataset is not registered");
        }

        RegisteredDataset dataset = datasets.findDatasetById(datasetId);
        if (dataset == null || !same(dataset.getDatasetVersion(), claims.getDatasetVersion())) {
            throw new TokenVerificationException("TOKEN_REGISTRY_SCOPE_MISMATCH",
                    "token dataset version does not match the registry");
        }
        if ("READ".equals(action) && !"ACTIVE".equals(dataset.getStatus())) {
            throw new TokenVerificationException("REPLICA_NOT_USABLE",
                    "dataset is not active for reads");
        }

        NodeManagement node = nodes.getNodeByName(nodeName);
        if (node == null || node.getNodeId() == null) {
            throw new TokenVerificationException("TOKEN_REGISTRY_SCOPE_MISMATCH",
                    "token target node is not registered");
        }
        DatasetReplica replica = datasets.findReplicaByDatasetNodePath(
                datasetId, node.getNodeId(), normalizePath(path));
        boolean replicaRequired = !system || "READ".equals(action)
                || "VERIFY".equals(action) || "DELETE".equals(action);
        if (replica == null && replicaRequired) {
            throw new TokenVerificationException("TOKEN_REGISTRY_SCOPE_MISMATCH",
                    "token path does not match a registered replica on this node");
        }
        if ("READ".equals(action) && !availability.evaluate(replica).isUsable()) {
            throw new TokenVerificationException("REPLICA_NOT_USABLE",
                    "registered replica is not usable for reads");
        }
    }

    private Long parseDatasetId(String value) {
        try {
            return Long.valueOf(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void consumeIfSingleUse(NodeAccessTokenClaims claims) {
        if (claims == null || !claims.isSingleUse()) return;
        if (tokenConsumptionMapper != null) {
            try {
                tokenConsumptionMapper.deleteExpired();
                tokenConsumptionMapper.consume(claims.getJti(), nodeName, claims.getAction(),
                        LocalDateTime.ofInstant(
                                Instant.ofEpochSecond(claims.getExpiresAtEpochSeconds()), ZoneOffset.UTC));
                return;
            } catch (DuplicateKeyException ex) {
                throw new TokenVerificationException("TOKEN_REPLAYED",
                        "single-use scoped access token was already consumed");
            } catch (DataAccessException ex) {
                throw new TokenVerificationException("TOKEN_REPLAY_STATE_UNAVAILABLE",
                        "single-use token replay ledger is unavailable");
            }
        }

        // Unit tests and deliberately database-free local profiles retain an
        // in-process fail-closed ledger. Cluster deployments always inject the
        // durable mapper above so a Pod restart cannot make a token reusable.
        long now = Instant.now().getEpochSecond();
        for (Map.Entry<String, Long> item : consumedSingleUseJtis.entrySet()) {
            Long expiresAt = item.getValue();
            if (expiresAt <= now) {
                consumedSingleUseJtis.remove(item.getKey(), expiresAt);
            }
        }
        Long alreadyConsumedUntil = consumedSingleUseJtis.putIfAbsent(
                claims.getJti(), claims.getExpiresAtEpochSeconds());
        if (alreadyConsumedUntil != null) {
            throw new TokenVerificationException("TOKEN_REPLAYED",
                    "single-use scoped access token was already consumed");
        }
    }

    private void audit(NodeAccessTokenClaims claims, String datasetId, String datasetVersion,
                       String path, String action, String decision, String reason) {
        if (auditMapper == null) return;
        LocalDateTime expiresAt = claims == null || claims.getExpiresAtEpochSeconds() <= 0 ? null
                : LocalDateTime.ofInstant(Instant.ofEpochSecond(claims.getExpiresAtEpochSeconds()),
                ZoneOffset.UTC);
        auditMapper.insert(claims == null ? null : claims.getJti(),
                claims == null ? "UNKNOWN" : claims.getSubject(), datasetId, datasetVersion,
                path, action == null ? null : action.toUpperCase(), nodeName, decision, reason, expiresAt);
    }

    private NodeAccessTokenClaims decode(String authorizationHeader) {
        String token = authorizationHeader;
        if (token != null && token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        } else if (token != null && token.startsWith("Topic4Scope ")) {
            token = token.substring(12).trim();
        }
        if (token == null || token.isEmpty()) {
            throw new TokenVerificationException("TOKEN_MISSING", "scoped access token is required");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new TokenVerificationException("TOKEN_MALFORMED", "scoped access token is malformed");
        }
        String signed = parts[0] + "." + parts[1];
        if (!java.security.MessageDigest.isEqual(signature(signed).getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII))) {
            throw new TokenVerificationException("TOKEN_SIGNATURE_INVALID", "token signature is invalid");
        }
        try {
            NodeAccessTokenClaims claims = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), NodeAccessTokenClaims.class);
            if (claims.getExpiresAtEpochSeconds() <= Instant.now().getEpochSecond()) {
                throw new TokenVerificationException("TOKEN_EXPIRED", "scoped access token has expired");
            }
            if (claims.getJti() == null || claims.getJti().isEmpty()
                    || claims.getSubject() == null || claims.getSubject().isEmpty()) {
                throw new TokenVerificationException("TOKEN_CLAIMS_INVALID", "token claims are incomplete");
            }
            return claims;
        } catch (TokenVerificationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TokenVerificationException("TOKEN_MALFORMED", "token payload is invalid");
        }
    }

    private String signature(String signed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getHmacSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private String normalizePath(String value) {
        String result = required(value, "path").replace('\\', '/');
        for (String segment : result.split("/")) {
            if ("..".equals(segment)) throw new TokenVerificationException(
                    "TOKEN_SCOPE_INVALID", "path traversal is not allowed");
        }
        return result;
    }

    private String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new TokenVerificationException("TOKEN_SCOPE_INVALID", name + " is required");
        }
        return value.trim();
    }

    private boolean same(String left, String right) {
        return left != null && left.equals(right);
    }

    public static class TokenVerificationException extends RuntimeException {
        private final String errorCode;

        public TokenVerificationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() { return errorCode; }
    }
}
