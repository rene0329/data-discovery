package org.example.logging;

import org.example.result.Result;
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
        if (failed && request instanceof ServletServerHttpRequest) {
            ((ServletServerHttpRequest) request).getServletRequest()
                    .setAttribute(OperationLogFilter.OUTCOME, "FAILED");
        }
        return body;
    }
}
