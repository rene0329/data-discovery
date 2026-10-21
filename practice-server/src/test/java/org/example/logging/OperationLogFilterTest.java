package org.example.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.example.result.Result;
import static org.junit.jupiter.api.Assertions.*;

class OperationLogFilterTest {
    @Test
    void successfulReadsAreLimitedPerRouteAndResumeAfterThirtySeconds() {
        OperationLogFilter filter = new OperationLogFilter();
        assertTrue(filter.shouldLogRead("/api/v1/datasets", 0));
        assertFalse(filter.shouldLogRead("/api/v1/datasets", 1000000000L));
        assertTrue(filter.shouldLogRead("/api/v1/nodes", 1000000000L));
        assertTrue(filter.shouldLogRead("/api/v1/datasets", 30000000000L));
    }

    @Test
    void logsBusinessFailureWithoutCredentialsAndRestoresContext() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationLogFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks");
        request.setQueryString("token=secret");
        request.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put("traceId", "previous");
        try {
            new OperationLogFilter().doFilter(request, response, (req, res) -> {
                assertEquals(response.getHeader("X-Request-ID"), MDC.get("traceId"));
                new OperationResultAdvice().beforeBodyWrite(Result.error("failed"), null, null, null,
                        new ServletServerHttpRequest(request), null);
            });
            String message = events.list.get(0).getFormattedMessage();
            assertTrue(message.contains("result=FAILED"));
            assertTrue(message.contains("httpStatus=200"));
            assertTrue(message.contains("durationMs="));
            assertFalse(message.contains("secret"));
            assertEquals("previous", MDC.get("traceId"));
        } finally {
            MDC.clear();
            logger.detachAppender(events);
        }
    }

    @Test
    void logsAcceptedSeparatelyFromCompleted() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationLogFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new OperationLogFilter().doFilter(new MockHttpServletRequest("POST", "/api/v1/tasks"),
                    response, (req, res) -> response.setStatus(202));
            assertTrue(events.list.get(0).getFormattedMessage().contains("result=ACCEPTED"));
            assertNull(MDC.get("traceId"));
        } finally {
            logger.detachAppender(events);
        }
    }
}
