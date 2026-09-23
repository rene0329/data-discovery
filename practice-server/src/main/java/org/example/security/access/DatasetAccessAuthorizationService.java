package org.example.security.access;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.service.DatasetReplicaAvailabilityService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Injectable authorization boundary used by the business-read controller and
 * internal copy/verify callers. The caller supplies the exact scope it needs;
 * the service never trusts a principal name from a request body.
 */
@Service
public class DatasetAccessAuthorizationService {
    private final DatasetAccessProperties properties;
    private final DatasetAccessTokenCodec tokenCodec;
    private final DatasetAccessAuditMapper auditMapper;
    private final DatasetRegistrationMapper datasets;
    private final NodeManagementMapper nodes;
    private final DatasetReplicaAvailabilityService availability;

    public DatasetAccessAuthorizationService(DatasetAccessProperties properties,
                                             DatasetAccessTokenCodec tokenCodec,
                                             DatasetAccessAuditMapper auditMapper,
                                             DatasetRegistrationMapper datasets,
                                             NodeManagementMapper nodes,
                                             DatasetReplicaAvailabilityService availability) {
        this.properties = properties;
        this.tokenCodec = tokenCodec;
        this.auditMapper = auditMapper;
        this.datasets = datasets;
        this.nodes = nodes;
        this.availability = availability;
    }

    public AccessAuthorizationResult authorizeAndIssue(String authorizationHeader,
                                                        AccessScope requested,
                                                        AccessAuditContext context) {
        AccessScope scope = normalize(requested);
        String principal = authenticateBasic(authorizationHeader, scope, context);
        if (!("READ".equals(scope.getAction()) || "VERIFY".equals(scope.getAction()))) {
            audit(principal, scope, context, null, "DENIED", "PUBLIC_ACTION_NOT_ALLOWED", null, null);
            throw new AccessAuthorizationException(HttpStatus.FORBIDDEN,
                    "PUBLIC_ACTION_NOT_ALLOWED",
                    "public test identities may only request READ or VERIFY tokens");
        }
        String rule = matchingRule(principal, scope);
        if (rule == null) {
            audit(principal, scope, context, null, "DENIED", "POLICY_DENIED", null, null);
            throw new AccessAuthorizationException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                    "principal is not permitted for the requested dataset action");
        }
        validateRegisteredScope(principal, scope, context, rule);

        return issue(principal, scope, context, rule, properties.getTokenTtlSeconds(), false);
    }

    /**
     * Issues the same narrow, short-lived token for trusted server-side callers
     * such as task input preparation and replica transfer. Keeping this method
     * in the authorization service avoids introducing an unaudited "internal"
     * token format.
     */
    public AccessAuthorizationResult issueInternal(AccessScope requested,
                                                   AccessAuditContext context) {
        AccessScope scope = normalize(requested);
        if (!properties.isEnabled()) {
            audit("SYSTEM", scope, context, "INTERNAL_SYSTEM", "DENIED",
                    "ACCESS_CONTROL_DISABLED", null, null);
            throw new AccessAuthorizationException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ACCESS_CONTROL_DISABLED", "dataset access control is disabled");
        }
        // A task token must remain valid for the longest accepted Job transfer.
        // Public reviewer tokens retain the shorter configured TTL.
        return issue("SYSTEM", scope, context, "INTERNAL_SYSTEM", 3600L, false);
    }

    /** Issues an internal token whose JTI may be consumed exactly once by the target Agent. */
    public AccessAuthorizationResult issueInternalOneTime(AccessScope requested,
                                                          AccessAuditContext context) {
        AccessScope scope = normalize(requested);
        if (!properties.isEnabled()) {
            audit("SYSTEM", scope, context, "INTERNAL_PRIVACY_ONE_TIME", "DENIED",
                    "ACCESS_CONTROL_DISABLED", null, null);
            throw new AccessAuthorizationException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ACCESS_CONTROL_DISABLED", "dataset access control is disabled");
        }
        return issue("SYSTEM", scope, context, "INTERNAL_PRIVACY_ONE_TIME", 3600L, true);
    }

    private AccessAuthorizationResult issue(String principal, AccessScope scope,
                                            AccessAuditContext context, String rule,
                                            long requestedTtlSeconds, boolean singleUse) {

        long now = Instant.now().getEpochSecond();
        long ttl = Math.max(1L, Math.min(requestedTtlSeconds, 3600L));
        AccessTokenClaims claims = new AccessTokenClaims();
        claims.setSubject(principal);
        claims.setDatasetId(scope.getDatasetId());
        claims.setDatasetVersion(scope.getDatasetVersion());
        claims.setPath(scope.getPath());
        claims.setAction(scope.getAction());
        claims.setTargetNode(scope.getTargetNode());
        claims.setIssuedAtEpochSeconds(now);
        claims.setExpiresAtEpochSeconds(now + ttl);
        claims.setJti(UUID.randomUUID().toString());
        claims.setSingleUse(singleUse);
        String token = tokenCodec.encode(claims);
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(claims.getExpiresAtEpochSeconds()), ZoneOffset.UTC);
        audit(principal, scope, context, rule, "ALLOWED", "TOKEN_ISSUED",
                claims.getJti(), expiresAt);

        AccessAuthorizationResult result = new AccessAuthorizationResult();
        result.setToken(token);
        result.setTokenType("Topic4Scope");
        result.setExpiresAt(Instant.ofEpochSecond(claims.getExpiresAtEpochSeconds()));
        result.setJti(claims.getJti());
        result.setPrincipal(principal);
        result.setScope(scope);
        return result;
    }

    /**
     * Public callers may only receive a token for a real registered replica.
     * This prevents a caller with access to dataset A from substituting the
     * node or path of dataset B in the request body.
     */
    private void validateRegisteredScope(String principal, AccessScope scope,
                                         AccessAuditContext context, String rule) {
        Long datasetId;
        try {
            datasetId = Long.valueOf(scope.getDatasetId());
        } catch (NumberFormatException ex) {
            denyScope(principal, scope, context, rule, "SCOPE_NOT_REGISTERED",
                    "public access requires a registered numeric dataset ID");
            return;
        }

        RegisteredDataset dataset = datasets.findDatasetById(datasetId);
        if (dataset == null || !equals(dataset.getDatasetVersion(), scope.getDatasetVersion())) {
            denyScope(principal, scope, context, rule, "SCOPE_NOT_REGISTERED",
                    "dataset ID and version do not identify a registered dataset");
        }
        if ("READ".equals(scope.getAction()) && !"ACTIVE".equals(dataset.getStatus())) {
            denyScope(principal, scope, context, rule, "REPLICA_NOT_USABLE",
                    "dataset is not active for reads");
        }

        NodeManagement node = nodes.getNodeByName(scope.getTargetNode());
        if (node == null || node.getNodeId() == null) {
            denyScope(principal, scope, context, rule, "SCOPE_NOT_REGISTERED",
                    "target node is not registered");
        }
        DatasetReplica replica = datasets.findReplicaByDatasetNodePath(
                datasetId, node.getNodeId(), scope.getPath());
        if (replica == null) {
            denyScope(principal, scope, context, rule, "SCOPE_NOT_REGISTERED",
                    "dataset, node and path do not identify one registered replica");
        }
        if ("READ".equals(scope.getAction()) && !availability.evaluate(replica).isUsable()) {
            denyScope(principal, scope, context, rule, "REPLICA_NOT_USABLE",
                    "registered replica is not usable for reads");
        }
    }

    private void denyScope(String principal, AccessScope scope, AccessAuditContext context,
                           String rule, String reason, String message) {
        audit(principal, scope, context, rule, "DENIED", reason, null, null);
        HttpStatus status = "REPLICA_NOT_USABLE".equals(reason)
                ? HttpStatus.CONFLICT : HttpStatus.FORBIDDEN;
        throw new AccessAuthorizationException(status, reason, message);
    }

    public AccessTokenClaims verifyAndAudit(String token,
                                            AccessScope expected,
                                            AccessAuditContext context) {
        AccessScope scope = normalize(expected);
        AccessTokenClaims claims;
        try {
            claims = tokenCodec.decodeAndVerify(stripTokenScheme(token));
        } catch (AccessAuthorizationException ex) {
            audit("UNKNOWN", scope, context, null, "DENIED", ex.getErrorCode(), null, null);
            throw ex;
        }
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(claims.getExpiresAtEpochSeconds()), ZoneOffset.UTC);
        if (!sameScope(claims, scope)) {
            audit(claims.getSubject(), scope, context, null, "DENIED", "TOKEN_SCOPE_MISMATCH",
                    claims.getJti(), expiresAt);
            throw new AccessAuthorizationException(HttpStatus.FORBIDDEN, "TOKEN_SCOPE_MISMATCH",
                    "scoped access token cannot be used for this dataset operation");
        }
        audit(claims.getSubject(), scope, context, "SIGNED_SCOPE", "ALLOWED",
                "TOKEN_VERIFIED", claims.getJti(), expiresAt);
        return claims;
    }

    public List<DatasetAccessAuditEvent> findAuditEvents(String requestId, String runId,
                                                          String principal, int limit) {
        return findAuditEvents(requestId, runId, principal, null, limit);
    }

    /** {@code decision} (ALLOWED / DENIED, case-insensitive) is optional and filtered in SQL. */
    public List<DatasetAccessAuditEvent> findAuditEvents(String requestId, String runId,
                                                          String principal, String decision,
                                                          int limit) {
        String normalizedDecision = trimToNull(decision);
        return auditMapper.find(trimToNull(requestId), trimToNull(runId), trimToNull(principal),
                normalizedDecision == null ? null : normalizedDecision.toUpperCase(Locale.ROOT),
                Math.max(1, Math.min(limit, 500)));
    }

    private String authenticateBasic(String authorizationHeader, AccessScope scope,
                                     AccessAuditContext context) {
        if (!properties.isEnabled()) {
            audit("UNKNOWN", scope, context, null, "DENIED", "ACCESS_CONTROL_DISABLED",
                    null, null);
            throw new AccessAuthorizationException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ACCESS_CONTROL_DISABLED", "dataset access control is disabled");
        }
        String presentedPrincipal = "UNKNOWN";
        String presentedSecret = "";
        try {
            if (authorizationHeader == null || !authorizationHeader.startsWith("Basic ")) {
                throw new IllegalArgumentException("missing Basic credentials");
            }
            String decoded = new String(Base64.getDecoder().decode(
                    authorizationHeader.substring(6).trim()), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator <= 0) throw new IllegalArgumentException("malformed Basic credentials");
            presentedPrincipal = decoded.substring(0, separator);
            presentedSecret = decoded.substring(separator + 1);
        } catch (Exception ex) {
            audit("UNKNOWN", scope, context, null, "DENIED", "CREDENTIALS_INVALID", null, null);
            throw new AccessAuthorizationException(HttpStatus.UNAUTHORIZED, "CREDENTIALS_INVALID",
                    "valid test credentials are required");
        }

        String authenticated = null;
        for (Map.Entry<String, String> configured : properties.getCredentials().entrySet()) {
            boolean nameMatches = constantTime(configured.getKey(), presentedPrincipal);
            boolean secretMatches = constantTime(configured.getValue(), presentedSecret);
            if (nameMatches && secretMatches) authenticated = configured.getKey();
        }
        if (authenticated == null) {
            audit(presentedPrincipal, scope, context, null, "DENIED", "CREDENTIALS_INVALID",
                    null, null);
            throw new AccessAuthorizationException(HttpStatus.UNAUTHORIZED, "CREDENTIALS_INVALID",
                    "valid test credentials are required");
        }
        return authenticated;
    }

    private String matchingRule(String principal, AccessScope scope) {
        List<String> rules = properties.getPermissions().getOrDefault(principal,
                Collections.emptyList());
        for (String configured : rules) {
            if (configured == null) continue;
            String[] parts = configured.trim().toUpperCase(Locale.ROOT).split(":", 2);
            if (parts.length == 2 && parts[0].equals(scope.getAction())
                    && ("*".equals(parts[1])
                    || parts[1].equals(scope.getDatasetId().toUpperCase(Locale.ROOT)))) {
                return configured;
            }
        }
        return null;
    }

    private AccessScope normalize(AccessScope value) {
        if (value == null) throw invalidScope("scope is required");
        String datasetId = required(value.getDatasetId(), "datasetId", 128);
        String version = required(value.getDatasetVersion(), "datasetVersion", 128);
        String path = required(value.getPath(), "path", 1024).replace('\\', '/');
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) throw invalidScope("path traversal is not allowed");
        }
        String action = required(value.getAction(), "action", 32).toUpperCase(Locale.ROOT);
        if (!(action.equals("READ") || action.equals("COPY") || action.equals("VERIFY")
                || action.equals("WRITE") || action.equals("DELETE"))) {
            throw invalidScope("unsupported dataset action");
        }
        String target = required(value.getTargetNode(), "targetNode", 128);
        return new AccessScope(datasetId, version, path, action, target);
    }

    private boolean sameScope(AccessTokenClaims claims, AccessScope scope) {
        return equals(claims.getDatasetId(), scope.getDatasetId())
                && equals(claims.getDatasetVersion(), scope.getDatasetVersion())
                && equals(claims.getPath(), scope.getPath())
                && equals(claims.getAction(), scope.getAction())
                && equals(claims.getTargetNode(), scope.getTargetNode());
    }

    private void audit(String principal, AccessScope scope, AccessAuditContext context,
                       String rule, String decision, String reason, String jti,
                       LocalDateTime expiresAt) {
        DatasetAccessAuditEvent event = new DatasetAccessAuditEvent();
        event.setRequestId(context == null ? null : trimToNull(context.getRequestId()));
        event.setRunId(context == null ? null : trimToNull(context.getRunId()));
        event.setClientIp(context == null ? null : trimToNull(context.getClientIp()));
        event.setJti(jti);
        event.setPrincipal(principal == null ? "UNKNOWN" : principal);
        event.setDatasetId(scope == null ? null : scope.getDatasetId());
        event.setDatasetVersion(scope == null ? null : scope.getDatasetVersion());
        event.setFilePath(scope == null ? null : scope.getPath());
        event.setAction(scope == null ? null : scope.getAction());
        event.setTargetNode(scope == null ? null : scope.getTargetNode());
        event.setPolicyRule(rule);
        event.setDecision(decision);
        event.setReason(reason);
        event.setTokenExpiresAt(expiresAt);
        auditMapper.insert(event);
    }

    private String stripTokenScheme(String token) {
        if (token != null && token.startsWith("Topic4Scope ")) return token.substring(12).trim();
        if (token != null && token.startsWith("Bearer ")) return token.substring(7).trim();
        return token;
    }

    private boolean constantTime(String configured, String presented) {
        byte[] left = configured == null ? new byte[0] : configured.getBytes(StandardCharsets.UTF_8);
        byte[] right = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    private boolean equals(String left, String right) {
        return left != null && left.equals(right);
    }

    private String required(String value, String field, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null) throw invalidScope(field + " is required");
        if (normalized.length() > maxLength) throw invalidScope(field + " is too long");
        if (normalized.indexOf('\0') >= 0) throw invalidScope(field + " contains invalid characters");
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private AccessAuthorizationException invalidScope(String message) {
        return new AccessAuthorizationException(HttpStatus.BAD_REQUEST, "INVALID_ACCESS_SCOPE", message);
    }
}
