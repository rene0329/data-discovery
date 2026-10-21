package org.example.logging;

import org.example.result.Result;
import org.example.dto.registration.TaskPreflightResult;
import org.example.dto.registration.TaskPreflightCheck;
import org.example.vo.ApiV1Response;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Distinguishes business failures returned with HTTP 200, without buffering response bodies. */
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
        boolean failed = body instanceof Result && !Integer.valueOf(1).equals(((Result<?>) body).getCode());
        failed |= body instanceof ApiV1Response && ((ApiV1Response<?>) body).getCode() != 0;
        String reason = null;
        if (body instanceof ApiV1Response) {
            ApiV1Response<?> result = (ApiV1Response<?>) body;
            if (failed) reason = result.getErrorCode();
            if (result.getData() instanceof TaskPreflightResult) {
                TaskPreflightResult preflight = (TaskPreflightResult) result.getData();
                if (!preflight.isValid()) {
                    failed = true;
                    reason = preflight.getChecks().stream().filter(check -> !check.isAvailable())
                            .map(TaskPreflightCheck::getErrorCode).filter(code -> code != null)
                            .findFirst().orElse("PREFLIGHT_REJECTED");
                }
            }
        }
        if (failed && request instanceof ServletServerHttpRequest) {
            ((ServletServerHttpRequest) request).getServletRequest()
                    .setAttribute(OperationLogFilter.OUTCOME, "FAILED");
            if (reason != null) {
                // Only bounded error codes are included; never copy arbitrary response messages.
                String safeReason = reason.replaceAll("[^A-Za-z0-9_:-]", "_");
                ((ServletServerHttpRequest) request).getServletRequest().setAttribute(
                        OperationLogFilter.REASON, safeReason.substring(0, Math.min(128, safeReason.length())));
            }
        }
        return body;
    }
}
