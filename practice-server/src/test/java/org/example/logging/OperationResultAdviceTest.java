package org.example.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.dto.registration.OperationResult;
import org.example.privacy.PrivacyComputeModels;
import org.example.security.aggregation.coordinator.SecureAggregationRun;
import org.example.vo.ApiResponse;
import org.example.vo.ApiV1Response;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class OperationResultAdviceTest {
    private ILoggingEvent log(String method, String path, Object body) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OperationLogFilter.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(events);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            request.setServletPath(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            new OperationLogFilter().doFilter(request, response, (req, res) -> {
                assertSame(body, new OperationResultAdvice().beforeBodyWrite(body, null, null, null,
                        new ServletServerHttpRequest(request), null));
            });
            assertEquals(200, response.getStatus());
            assertEquals(1, events.list.size());
            return events.list.get(0);
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previous);
        }
    }

    @Test
    void invalidPrivacyPreflightIsWarnButWarningsAloneAreNotFailure() throws Exception {
        PrivacyComputeModels.PreflightResult result = new PrivacyComputeModels.PreflightResult();
        result.getErrors().add("private failure detail");
        ILoggingEvent event = log("POST", "/api/v1/privacy-computing/jobs/preflight", ApiV1Response.ok(result));
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("reason=PRIVACY_PREFLIGHT_REJECTED"));
        assertFalse(event.getFormattedMessage().contains("private failure detail"));
        result.setValid(true);
        result.getErrors().clear();
        result.getWarnings().add("experimental provider");
        assertEquals(Level.INFO, log("POST", "/api/v1/privacy-computing/jobs/preflight", ApiV1Response.ok(result)).getLevel());
    }

    @Test
    void failedDiscoveryAndNoEligibleResourcesAreNotSuccess() throws Exception {
        OperationResult failure = OperationResult.discovery("run", 2, 0, Collections.singletonList("secret-resource"));
        ILoggingEvent event = log("POST", "/api/v1/node-discovery-runs", ApiV1Response.ok(failure));
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("result=FAILED"));
        assertFalse(event.getFormattedMessage().contains("secret-resource"));
        event = log("POST", "/api/v1/dataset-discovery-runs",
                ApiV1Response.ok(OperationResult.discovery("run", 0, 0, Collections.emptyList())));
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("result=NO_ELIGIBLE_RESOURCE"));
    }

    @Test
    void partialDiscoveryRemainsDistinctFromCompleteSuccess() throws Exception {
        ILoggingEvent event = log("POST", "/api/v1/dataset-discovery-runs",
                ApiV1Response.ok(OperationResult.discovery("run", 2, 1, Collections.singletonList("resource"))));
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("result=PARTIAL_SUCCESS"));
        assertEquals(Level.INFO, log("POST", "/api/v1/dataset-discovery-runs",
                ApiV1Response.ok(OperationResult.discovery("run", 2, 2, Collections.emptyList()))).getLevel());
    }

    @Test
    void legacyMapFailureAndEnvelopeErrorsAreRecognized() throws Exception {
        ILoggingEvent event = log("DELETE", "/common/clearTasks",
                ApiResponse.ok(Collections.singletonMap("success", false)));
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("result=FAILED"));
        assertEquals(Level.WARN, log("POST", "/legacy", ApiResponse.error(400, "private message")).getLevel());
        assertEquals(Level.ERROR, log("POST", "/legacy", ApiResponse.error(500, "private message")).getLevel());
        assertEquals(Level.ERROR, log("POST", "/api/v1/example", ApiV1Response.error(500, "EXAMPLE_FAILED", "private message")).getLevel());
    }

    @Test
    void historicalFailureAndIntentionalRejectionDoNotBecomeRequestFailures() throws Exception {
        SecureAggregationRun historical = new SecureAggregationRun();
        historical.setStatus("FAILED");
        ILoggingEvent event = log("GET", "/api/v1/security/secure-aggregation/runs/old", ApiV1Response.ok(historical));
        assertEquals(Level.DEBUG, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("result=SUCCESS"));
        PrivacyComputeModels.JobView rejected = new PrivacyComputeModels.JobView();
        rejected.setStatus(PrivacyComputeModels.JobStatus.ABORTED);
        event = log("POST", "/api/v1/privacy-computing/jobs/old/reject", ApiV1Response.ok(rejected));
        assertEquals(Level.INFO, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("result=SUCCESS"));
    }

    @Test
    void legacyEchoEndpointDoesNotClaimTaskCreation() throws Exception {
        ILoggingEvent event = log("POST", "/common/submitDatasets",
                ApiResponse.ok(Collections.singletonMap("success", true)));
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("result=NO_OPERATION"));
        assertTrue(event.getFormattedMessage().contains("reason=LEGACY_PLACEHOLDER"));
    }

    @Test
    void legacyAsyncStartIsAcceptedUntilFinished() throws Exception {
        SecureAggregationRun pending = new SecureAggregationRun();
        pending.setStatus("PENDING");
        ILoggingEvent event = log("POST", "/api/v1/security/secure-aggregation/runs", ApiV1Response.ok(pending));
        assertEquals(Level.INFO, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("result=ACCEPTED"));
        pending.setStatus("FAILED");
        assertEquals(Level.WARN, log("POST", "/api/v1/security/secure-aggregation/runs", ApiV1Response.ok(pending)).getLevel());
    }
}
