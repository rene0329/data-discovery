package org.example.controller;

import org.example.model.NodeReadRequest;
import org.example.model.NodeReadResult;
import org.example.security.access.DatasetAccessScopeVerifier;
import org.example.service.NodeDatasetReadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@RestController("nodeDatasetAccessController")
@RequestMapping("/data-discovery/access")
public class DatasetAccessController {
    private final NodeDatasetReadService reads;
    private final DatasetAccessScopeVerifier tokens;

    public DatasetAccessController(NodeDatasetReadService reads, DatasetAccessScopeVerifier tokens) {
        this.reads = reads;
        this.tokens = tokens;
    }

    @PostMapping("/read")
    public ResponseEntity<?> read(
            @RequestBody NodeReadRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                    value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        try {
            tokens.verify(authorization, request == null ? null : request.getDatasetId(),
                    request == null ? null : request.getDatasetVersion(),
                    request == null ? null : request.getSourcePath(), "READ");
            return ResponseEntity.ok(reads.read(request));
        } catch (DatasetAccessScopeVerifier.TokenVerificationException error) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    Collections.singletonMap("error", error.getErrorCode()));
        }
        catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", error.getMessage()));
        } catch (IOException error) {
            return ResponseEntity.unprocessableEntity().body(Collections.singletonMap("error", error.getMessage()));
        }
    }

    @DeleteMapping("/cache")
    public Map<String, String> clear(@RequestParam(required = false) String cacheKey) {
        reads.clear(cacheKey);
        return Collections.singletonMap("status", "ok");
    }
}
