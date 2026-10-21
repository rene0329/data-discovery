package org.example.logging;

import org.example.result.Result;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.TaskPreflightResult;
import org.example.dto.registration.TaskPreflightCheck;
import org.example.privacy.PrivacyComputeModels;
import org.example.security.aggregation.coordinator.SecureAggregationRun;
import org.example.vo.ApiResponse;
import org.example.vo.ApiV1Response;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** Classifies explicit operation results without logging response bodies or changing API semantics. */
@RestControllerAdvice
public class OperationResultAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter type, MediaType mediaType,
                                  Class<? extends HttpMessageConverter<?>> converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest)) return body;
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        Object data = null;
        int code = 0;
        String errorCode = null;
        if (body instanceof ApiV1Response) {
            ApiV1Response<?> envelope = (ApiV1Response<?>) body;
            code = envelope.getCode();
            errorCode = envelope.getErrorCode();
            data = envelope.getData();
        } else if (body instanceof ApiResponse) {
            ApiResponse<?> envelope = (ApiResponse<?>) body;
            code = envelope.getCode();
            data = envelope.getData();
        } else if (body instanceof Result) {
            Result<?> envelope = (Result<?>) body;
            code = Integer.valueOf(1).equals(envelope.getCode()) ? 0 : 1;
            data = envelope.getData();
        }
        if (code != 0) {
            mark(servletRequest, "FAILED", errorCode == null ? "BUSINESS_RESPONSE_ERROR" : errorCode);
            if (code >= 500) servletRequest.setAttribute(OperationLogFilter.SERVER_ERROR, true);
            return body;
        }
        if (data instanceof TaskPreflightResult) {
            TaskPreflightResult preflight = (TaskPreflightResult) data;
            if (!preflight.isValid()) {
                String reason = preflight.getChecks().stream().filter(check -> !check.isAvailable())
                        .map(TaskPreflightCheck::getErrorCode).filter(value -> value != null)
                        .findFirst().orElse("PREFLIGHT_REJECTED");
                mark(servletRequest, "FAILED", reason);
            }
        } else if (data instanceof PrivacyComputeModels.PreflightResult) {
            if (!((PrivacyComputeModels.PreflightResult) data).isValid()) {
                mark(servletRequest, "FAILED", "PRIVACY_PREFLIGHT_REJECTED");
            }
        } else if (data instanceof OperationResult) {
            OperationResult result = (OperationResult) data;
            if (!result.isSuccess()) {
                String outcome = "NO_ELIGIBLE_RESOURCE".equals(result.getStatus())
                        ? "NO_ELIGIBLE_RESOURCE" : "FAILED";
                mark(servletRequest, outcome, result.getStatus() == null ? "OPERATION_FAILED" : result.getStatus());
            } else if ("PARTIAL_SUCCESS".equals(result.getStatus())
                    || result.getFailedResources() != null && !result.getFailedResources().isEmpty()) {
                mark(servletRequest, "PARTIAL_SUCCESS", "SOME_RESOURCES_FAILED");
            }
        } else if (data instanceof Map && isMutation(servletRequest)) {
            if (Boolean.FALSE.equals(((Map<?, ?>) data).get("success"))) {
                mark(servletRequest, "FAILED", "OPERATION_REJECTED");
            } else if ("POST".equals(servletRequest.getMethod())
                    && "/common/submitDatasets".equals(servletRequest.getServletPath())) {
                mark(servletRequest, "NO_OPERATION", "LEGACY_PLACEHOLDER");
            }
        } else if (data instanceof SecureAggregationRun && "POST".equals(servletRequest.getMethod())) {
            String status = ((SecureAggregationRun) data).getStatus();
            if ("PENDING".equals(status) || "RUNNING".equals(status)) {
                mark(servletRequest, "ACCEPTED", null);
            } else if ("FAILED".equals(status)) {
                mark(servletRequest, "FAILED", "SECURE_AGGREGATION_FAILED");
            }
        }
        return body;
    }

    private boolean isMutation(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private void mark(HttpServletRequest request, String outcome, String reason) {
        request.setAttribute(OperationLogFilter.OUTCOME, outcome);
        if (reason == null) return;
        // Keep only bounded codes, never raw error messages or failed-resource contents.
        String safeReason = reason.replaceAll("[^A-Za-z0-9_:-]", "_");
        request.setAttribute(OperationLogFilter.REASON,
                safeReason.substring(0, Math.min(128, safeReason.length())));
    }
}
