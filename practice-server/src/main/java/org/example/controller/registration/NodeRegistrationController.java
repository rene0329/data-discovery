package org.example.controller.registration;

import org.example.dto.registration.NodeCandidateView;
import org.example.dto.registration.NodeDiscoveryRequest;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisterNodeRequest;
import org.example.dto.registration.RegisteredNodeView;
import org.example.dto.registration.UpdateNodeRequest;
import org.example.service.NodeRegistrationService;
import org.example.vo.ApiV1Response;
import org.example.vo.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class NodeRegistrationController {
    private final NodeRegistrationService service;

    public NodeRegistrationController(NodeRegistrationService service) {
        this.service = service;
    }

    @PostMapping("/node-discovery-runs")
    public ResponseEntity<ApiV1Response<OperationResult>> discover(
            @RequestBody(required = false) NodeDiscoveryRequest request) {
        String operationId = service.discover(request == null ? Collections.emptySet() : request.getClusterIds());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiV1Response.ok(OperationResult.accepted(operationId, "node discovery completed")));
    }

    @GetMapping("/node-candidates")
    public ApiV1Response<PageResult<NodeCandidateView>> candidates(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String clusterId) {
        List<NodeCandidateView> candidates = service.listCandidates(query, clusterId);
        return ApiV1Response.ok(PageResult.of(candidates, page, pageSize));
    }

    @GetMapping("/nodes")
    public ApiV1Response<PageResult<RegisteredNodeView>> nodes(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean enabled) {
        List<RegisteredNodeView> nodes = service.listNodes(query, status, enabled);
        return ApiV1Response.ok(PageResult.of(nodes, page, pageSize));
    }

    @PostMapping("/nodes")
    public ResponseEntity<ApiV1Response<RegisteredNodeView>> register(
            @RequestBody RegisterNodeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        RegisteredNodeView node = service.register(request, requestId(requestId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiV1Response.ok(node));
    }

    @GetMapping("/nodes/{nodeId}")
    public ApiV1Response<RegisteredNodeView> get(@PathVariable Integer nodeId) {
        return ApiV1Response.ok(service.getNode(nodeId));
    }

    @PatchMapping("/nodes/{nodeId}")
    public ApiV1Response<RegisteredNodeView> update(
            @PathVariable Integer nodeId,
            @RequestBody UpdateNodeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return ApiV1Response.ok(service.update(nodeId, request, requestId(requestId)));
    }

    @PostMapping("/nodes/{nodeId}/verify")
    public ApiV1Response<RegisteredNodeView> verify(
            @PathVariable Integer nodeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return ApiV1Response.ok(service.verify(nodeId, requestId(requestId)));
    }

    @PostMapping("/nodes/{nodeId}/sync")
    public ApiV1Response<RegisteredNodeView> sync(@PathVariable Integer nodeId) {
        return ApiV1Response.ok(service.sync(nodeId));
    }

    @PostMapping("/nodes/{nodeId}/enable")
    public ApiV1Response<RegisteredNodeView> enable(
            @PathVariable Integer nodeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return ApiV1Response.ok(service.enable(nodeId, requestId(requestId)));
    }

    @PostMapping("/nodes/{nodeId}/disable")
    public ApiV1Response<RegisteredNodeView> disable(
            @PathVariable Integer nodeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return ApiV1Response.ok(service.disable(nodeId, requestId(requestId)));
    }

    @DeleteMapping("/nodes/{nodeId}")
    public ApiV1Response<OperationResult> unregister(
            @PathVariable Integer nodeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        service.unregister(nodeId, requestId(requestId));
        return ApiV1Response.ok(OperationResult.completed("node unregistered"));
    }

    private String requestId(String requestId) {
        return requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString()
                : requestId.trim();
    }
}
