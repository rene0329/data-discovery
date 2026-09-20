package org.example.controller.registration;

import org.example.auth.AuthenticatedUser;
import org.example.auth.CurrentUserService;
import org.example.privacy.PrivacyComputeModels.ActionRequest;
import org.example.privacy.PrivacyComputeModels.DecisionRequest;
import org.example.privacy.PrivacyComputeModels.EventRecord;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.JobView;
import org.example.privacy.PrivacyComputeModels.PreflightResult;
import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ResultView;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.example.privacy.PrivacyComputeService;
import org.example.service.ApiIdempotencyService;
import org.example.vo.ApiV1Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/privacy-computing")
public class PrivacyComputeController {
    private final PrivacyComputeService service;
    private final ApiIdempotencyService idempotency;
    private final CurrentUserService currentUsers;

    public PrivacyComputeController(PrivacyComputeService service, ApiIdempotencyService idempotency,
                                    CurrentUserService currentUsers) {
        this.service = service;
        this.idempotency = idempotency;
        this.currentUsers = currentUsers;
    }

    @GetMapping("/capabilities")
    public ApiV1Response<List<ProviderCapability>> capabilities() {
        return ApiV1Response.ok(service.capabilities());
    }

    @GetMapping("/templates")
    public ApiV1Response<List<TemplateDefinition>> templates() {
        return ApiV1Response.ok(service.templates());
    }

    @PostMapping("/jobs/preflight")
    public ApiV1Response<PreflightResult> preflight(
            @RequestBody JobSpec request) {
        return ApiV1Response.ok(service.preflight(request, currentUsers.require()));
    }

    @PostMapping("/jobs")
    public ResponseEntity<ApiV1Response<JobView>> create(
            @RequestBody JobSpec request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        AuthenticatedUser principal = currentUsers.require();
        JobView result = idempotency.execute("PRIVACY_JOB", "CREATE", idempotencyKey,
                String.valueOf(principal.getUserId()), request, JobView.class,
                () -> service.create(request, idempotencyKey, principal), JobView::getJobId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiV1Response.ok(result));
    }

    @GetMapping("/jobs")
    public ApiV1Response<List<JobView>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiV1Response.ok(service.listAuthorized(status, limit, currentUsers.require()));
    }

    @GetMapping("/approvals/pending")
    public ApiV1Response<List<JobView>> pendingApprovals(
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiV1Response.ok(service.pendingApprovals(limit, currentUsers.require()));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiV1Response<JobView> get(@PathVariable String jobId) {
        return ApiV1Response.ok(service.getAuthorized(jobId, currentUsers.require()));
    }

    @GetMapping("/jobs/{jobId}/events")
    public ApiV1Response<List<EventRecord>> events(
            @PathVariable String jobId) {
        return ApiV1Response.ok(service.eventsAuthorized(jobId, currentUsers.require()));
    }

    @GetMapping("/jobs/{jobId}/result")
    public ApiV1Response<ResultView> result(
            @PathVariable String jobId) {
        return ApiV1Response.ok(service.result(jobId, currentUsers.require()));
    }

    @GetMapping("/jobs/{jobId}/evidence")
    public ApiV1Response<Map<String, Object>> evidence(
            @PathVariable String jobId) {
        return ApiV1Response.ok(service.evidence(jobId, currentUsers.require()));
    }

    @PostMapping("/jobs/{jobId}/approve")
    public ApiV1Response<JobView> approve(
            @PathVariable String jobId,
            @RequestBody DecisionRequest request) {
        return ApiV1Response.ok(service.approve(jobId, currentUsers.require(), request));
    }

    @PostMapping("/jobs/{jobId}/reject")
    public ApiV1Response<JobView> reject(
            @PathVariable String jobId,
            @RequestBody DecisionRequest request) {
        return ApiV1Response.ok(service.reject(jobId, currentUsers.require(), request));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ApiV1Response<JobView> cancel(
            @PathVariable String jobId,
            @RequestBody(required = false) ActionRequest request) {
        return ApiV1Response.ok(service.cancel(jobId, currentUsers.require(), request));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<ApiV1Response<JobView>> retry(
            @PathVariable String jobId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        AuthenticatedUser principal = currentUsers.require();
        JobView result = idempotency.execute("PRIVACY_JOB", "RETRY", idempotencyKey,
                jobId + ":" + principal.getUserId(), null, JobView.class,
                () -> service.retry(jobId, principal), JobView::getAttemptId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiV1Response.ok(result));
    }
}
