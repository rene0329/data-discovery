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
    void mergesOnlyRepeatedPolling401AndReportsCountAndRecovery() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationLogFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        OperationLogFilter filter = new OperationLogFilter(now::get);
        try {
            for (int i = 0; i < 3; i++) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/datasets"), response,
                        (req, res) -> response.setStatus(401));
            }
            assertEquals(1, events.list.size());
            now.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(60));
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/datasets"), response,
                    (req, res) -> response.setStatus(401));
            assertTrue(events.list.get(1).getFormattedMessage().contains("已合并告警=3次"));
            for (int i = 0; i < 2; i++) {
                MockHttpServletResponse denied = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/tasks"), denied,
                        (req, res) -> denied.setStatus(401));
            }
            assertEquals(4, events.list.size());
            MockHttpServletResponse repeated = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/datasets"), repeated,
                    (req, res) -> repeated.setStatus(401));
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/datasets"),
                    new MockHttpServletResponse(), (req, res) -> {});
            assertTrue(events.list.stream().anyMatch(e -> e.getFormattedMessage().contains("轮询访问已恢复")));
        } finally { logger.detachAppender(events); }
    }

    @Test
    void deniedPreflightIsWarnEvenWhenEnvelopeAndHttpAreSuccessful() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationLogFilter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            for (boolean allowed : new boolean[]{false, true}) {
                MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks/preflight");
                new OperationLogFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    org.example.dto.registration.TaskPreflightCheck check = new org.example.dto.registration.TaskPreflightCheck(
                            "DATASET", "1", null, allowed, allowed ? "AVAILABLE" : "ACCESS_DENIED",
                            allowed ? null : "DATASET_ACCESS_DENIED", null);
                    Object body = org.example.vo.ApiV1Response.ok(new org.example.dto.registration.TaskPreflightResult(
                            java.util.Collections.singletonList(check)));
                    new OperationResultAdvice().beforeBodyWrite(body, null, null, null,
                            new ServletServerHttpRequest(request), null);
                });
            }
            assertEquals(ch.qos.logback.classic.Level.WARN, events.list.get(0).getLevel());
            assertTrue(events.list.get(0).getFormattedMessage().contains("result=FAILED"));
            assertTrue(events.list.get(0).getFormattedMessage().contains("reason=DATASET_ACCESS_DENIED"));
            assertEquals(ch.qos.logback.classic.Level.INFO, events.list.get(1).getLevel());
            assertTrue(events.list.get(1).getFormattedMessage().contains("result=SUCCESS"));
        } finally {
            logger.detachAppender(events);
        }
    }

    @Test
    void queryIsDebugMutationIsInfoAndServerFailureIsError() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationLogFilter.class);
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            OperationLogFilter filter = new OperationLogFilter();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/datasets"),
                    new MockHttpServletResponse(), (req, res) -> {});
            filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/tasks"),
                    new MockHttpServletResponse(), (req, res) -> {});
            MockHttpServletResponse error = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/datasets"),
                    error, (req, res) -> error.setStatus(500));
            assertEquals(ch.qos.logback.classic.Level.DEBUG, events.list.get(0).getLevel());
            assertEquals(ch.qos.logback.classic.Level.INFO, events.list.get(1).getLevel());
            assertEquals(ch.qos.logback.classic.Level.ERROR, events.list.get(2).getLevel());
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previous);
        }
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
