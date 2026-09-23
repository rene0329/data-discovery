package org.example.security.access;

import org.example.vo.ApiV1Response;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/security/access")
public class DatasetAccessSecurityController {
    private final DatasetAccessAuthorizationService service;

    public DatasetAccessSecurityController(DatasetAccessAuthorizationService service) {
        this.service = service;
    }

    @PostMapping("/authorizations")
    public ResponseEntity<ApiV1Response<AccessAuthorizationResult>> authorize(
            @RequestHeader(value = "X-Reviewer-Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-Run-Id", required = false) String runId,
            @RequestBody AccessScope scope,
            HttpServletRequest servletRequest) {
        try {
            AccessAuthorizationResult result = service.authorizeAndIssue(authorization, scope,
                    context(requestId, runId, servletRequest));
            return ResponseEntity.ok(ApiV1Response.ok(result));
        } catch (AccessAuthorizationException ex) {
            return ResponseEntity.status(ex.getStatus()).body(ApiV1Response.error(
                    ex.getStatus().value(), ex.getErrorCode(), ex.getMessage()));
        }
    }

    @PostMapping("/verifications")
    public ResponseEntity<ApiV1Response<AccessTokenClaims>> verify(
            @RequestHeader(value = "X-Dataset-Authorization", required = false) String token,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-Run-Id", required = false) String runId,
            @RequestBody AccessScope expected,
            HttpServletRequest servletRequest) {
        try {
            return ResponseEntity.ok(ApiV1Response.ok(service.verifyAndAudit(token, expected,
                    context(requestId, runId, servletRequest))));
        } catch (AccessAuthorizationException ex) {
            return ResponseEntity.status(ex.getStatus()).body(ApiV1Response.error(
                    ex.getStatus().value(), ex.getErrorCode(), ex.getMessage()));
        }
    }

    @GetMapping("/events")
    public ApiV1Response<List<DatasetAccessAuditEvent>> events(
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String principal,
            @RequestParam(required = false) String decision,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiV1Response.ok(service.findAuditEvents(requestId, runId, principal, decision, limit));
    }

    private AccessAuditContext context(String requestId, String runId,
                                       HttpServletRequest servletRequest) {
        String effectiveRequestId = requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId.trim();
        return new AccessAuditContext(effectiveRequestId, runId,
                servletRequest == null ? null : servletRequest.getRemoteAddr());
    }
}
