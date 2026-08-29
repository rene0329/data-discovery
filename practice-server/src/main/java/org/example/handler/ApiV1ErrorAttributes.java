package org.example.handler;

import org.example.vo.ApiV1Response;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import javax.servlet.RequestDispatcher;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps errors produced before controller resolution (notably 404 and 405)
 * aligned with the registration API envelope without changing legacy routes.
 */
@Component
public class ApiV1ErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest,
                                                   ErrorAttributeOptions options) {
        Object requestUri = webRequest.getAttribute(
                RequestDispatcher.ERROR_REQUEST_URI, RequestAttributes.SCOPE_REQUEST);
        if (!(requestUri instanceof String) || !isApiV1Path((String) requestUri)) {
            return super.getErrorAttributes(webRequest, options);
        }

        int status = status(webRequest);
        ApiV1Response<Object> response = ApiV1Response.error(
                status, errorCode(status), message(status));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("code", response.getCode());
        attributes.put("msg", response.getMsg());
        attributes.put("errorCode", response.getErrorCode());
        attributes.put("data", response.getData());
        attributes.put("traceId", response.getTraceId());
        attributes.put("timestamp", response.getTimestamp());
        return attributes;
    }

    private boolean isApiV1Path(String requestUri) {
        return "/api/v1".equals(requestUri) || requestUri.startsWith("/api/v1/");
    }

    private int status(WebRequest webRequest) {
        Object value = webRequest.getAttribute(
                RequestDispatcher.ERROR_STATUS_CODE, RequestAttributes.SCOPE_REQUEST);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 500;
        }
    }

    private String errorCode(int status) {
        switch (status) {
            case 400:
            case 422:
                return "INVALID_ARGUMENT";
            case 401:
                return "UNAUTHORIZED";
            case 403:
                return "FORBIDDEN";
            case 404:
                return "RESOURCE_NOT_FOUND";
            case 405:
                return "METHOD_NOT_ALLOWED";
            case 406:
                return "NOT_ACCEPTABLE";
            case 409:
                return "RESOURCE_CONFLICT";
            case 415:
                return "UNSUPPORTED_MEDIA_TYPE";
            case 500:
                return "INTERNAL_ERROR";
            default:
                return "REQUEST_FAILED";
        }
    }

    private String message(int status) {
        switch (status) {
            case 400:
                return "invalid request";
            case 401:
                return "authentication is required";
            case 403:
                return "access is forbidden";
            case 404:
                return "API endpoint was not found";
            case 405:
                return "HTTP method is not supported for this endpoint";
            case 406:
                return "requested response type is not supported";
            case 409:
                return "request conflicts with current resource state";
            case 415:
                return "Content-Type must be application/json";
            case 422:
                return "request validation failed";
            case 500:
                return "internal server error";
            default:
                return "request failed";
        }
    }
}
