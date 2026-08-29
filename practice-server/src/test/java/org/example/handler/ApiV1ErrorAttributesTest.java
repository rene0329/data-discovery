package org.example.handler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import javax.servlet.RequestDispatcher;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiV1ErrorAttributesTest {

    private final ApiV1ErrorAttributes attributes = new ApiV1ErrorAttributes();

    @Test
    void apiV1NotFoundUsesUnifiedEnvelope() {
        Map<String, Object> result = error("/api/v1/missing", 404);

        assertEquals(404, result.get("code"));
        assertEquals("RESOURCE_NOT_FOUND", result.get("errorCode"));
        assertNotNull(result.get("traceId"));
        assertNotNull(result.get("timestamp"));
        assertTrue(result.containsKey("data"));
    }

    @Test
    void apiV1MethodNotAllowedUsesUnifiedEnvelope() {
        Map<String, Object> result = error("/api/v1/nodes/1", 405);

        assertEquals(405, result.get("code"));
        assertEquals("METHOD_NOT_ALLOWED", result.get("errorCode"));
    }

    @Test
    void legacyRouteKeepsSpringErrorShape() {
        Map<String, Object> result = error("/legacy/missing", 404);

        assertEquals(404, result.get("status"));
        assertTrue(result.containsKey("error"));
    }

    private Map<String, Object> error(String uri, int status) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, uri);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        return attributes.getErrorAttributes(new ServletWebRequest(request),
                ErrorAttributeOptions.defaults());
    }
}
