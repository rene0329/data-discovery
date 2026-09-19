package org.example.security.aggregation.worker;

import org.example.security.aggregation.SecureAggregationWire;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/secure-aggregation/worker/runs")
public class SecureAggregationWorkerController {
    private final SecureAggregationWorkerService service;

    public SecureAggregationWorkerController(SecureAggregationWorkerService service) {
        this.service = service;
    }

    @PostMapping("/{runId}/prepare")
    public ResponseEntity<?> prepare(
            @PathVariable String runId,
            @RequestHeader(value = "X-Aggregation-Auth", required = false) String authentication,
            @RequestBody SecureAggregationWire.PrepareRequest request) {
        if (!service.authenticate("prepare", runId, authentication)) return unauthorized();
        try {
            return ResponseEntity.ok(service.prepare(runId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(ex.getMessage()));
        }
    }

    @PostMapping("/{runId}/contribute")
    public ResponseEntity<?> contribute(
            @PathVariable String runId,
            @RequestHeader(value = "X-Aggregation-Auth", required = false) String authentication,
            @RequestBody SecureAggregationWire.ContributionRequest request) {
        if (!service.authenticate("contribute", runId, authentication)) return unauthorized();
        try {
            return ResponseEntity.ok(service.contribute(runId, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(ex.getMessage()));
        }
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error("valid worker authentication is required"));
    }

    private Map<String, String> error(String message) {
        return Collections.singletonMap("error", message == null ? "request failed" : message);
    }
}
