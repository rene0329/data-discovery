package org.example.security.aggregation.coordinator;

import org.example.vo.ApiV1Response;
import org.example.privacy.LegacySecureAggregationBridge;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@RestController
@RequestMapping("/api/v1/security/secure-aggregation/runs")
public class SecureAggregationController {
    private final SecureAggregationCoordinatorService service;
    private final LegacySecureAggregationBridge privacyBridge;

    public SecureAggregationController(SecureAggregationCoordinatorService service,
                                       LegacySecureAggregationBridge privacyBridge) {
        this.service = service;
        this.privacyBridge = privacyBridge;
    }

    @PostMapping
    public ResponseEntity<ApiV1Response<SecureAggregationRun>> start(
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId,
            @RequestBody(required = false) JobSpec request) {
        SecureAggregationRun run = privacyBridge.start(requestId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiV1Response.ok(run));
    }

    @GetMapping("/{runId}")
    public ApiV1Response<SecureAggregationRun> get(
            @PathVariable String runId) {
        return ApiV1Response.ok(runId.startsWith("pcj-")
                ? privacyBridge.get(runId) : service.get(runId));
    }

    @GetMapping("/{runId}/events")
    public ApiV1Response<List<SecureAggregationMessageEvent>> events(
            @PathVariable String runId) {
        return ApiV1Response.ok(runId.startsWith("pcj-")
                ? privacyBridge.events(runId) : service.events(runId));
    }
}
