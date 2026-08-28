package org.example.controller.registration;

import org.example.dto.registration.BindRuntimeImageRequest;
import org.example.dto.registration.DatasetDiscoveryRequest;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisterDatasetRequest;
import org.example.dto.registration.RegisterReplicaRequest;
import org.example.dto.registration.RegisteredDatasetView;
import org.example.dto.registration.UpdateDatasetRequest;
import org.example.entity.DatasetDiscoveryCandidate;
import org.example.entity.DatasetReplica;
import org.example.exception.RegistrationException;
import org.example.service.DatasetRegistrationService;
import org.example.vo.ApiV1Response;
import org.example.vo.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
public class DatasetRegistrationController {
    private final DatasetRegistrationService service;

    public DatasetRegistrationController(DatasetRegistrationService service) {
        this.service = service;
    }

    @PostMapping("/dataset-discovery-runs")
    public ResponseEntity<ApiV1Response<OperationResult>> discover(
            @RequestBody(required = false) DatasetDiscoveryRequest request) {
        String operationId = service.discover(request == null ? Collections.emptySet() : request.getNodeIds());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiV1Response.ok(OperationResult.accepted(operationId, "dataset discovery triggered")));
    }

    @GetMapping("/dataset-candidates")
    public ApiV1Response<PageResult<DatasetDiscoveryCandidate>> candidates(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Integer nodeId) {
        List<DatasetDiscoveryCandidate> candidates = service.listCandidates(query, nodeId);
        return ApiV1Response.ok(PageResult.of(candidates, page, pageSize));
    }

    @GetMapping("/datasets")
    public ApiV1Response<PageResult<RegisteredDatasetView>> datasets(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String status) {
        return ApiV1Response.ok(PageResult.of(service.listDatasets(query, status), page, pageSize));
    }

    @PostMapping("/datasets")
    public ResponseEntity<ApiV1Response<RegisteredDatasetView>> register(
            @RequestBody RegisterDatasetRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        RegisteredDatasetView dataset = service.register(request, requestId(requestId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiV1Response.ok(dataset));
    }

    @GetMapping("/datasets/{datasetId}")
    public ApiV1Response<RegisteredDatasetView> get(@PathVariable Long datasetId) {
        return ApiV1Response.ok(service.getDataset(datasetId));
    }

    @PatchMapping("/datasets/{datasetId}")
    public ApiV1Response<RegisteredDatasetView> update(
            @PathVariable Long datasetId,
            @RequestBody UpdateDatasetRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return ApiV1Response.ok(service.update(datasetId, request, requestId(requestId)));
    }

    @PostMapping("/datasets/{datasetId}/verify")
    public ApiV1Response<RegisteredDatasetView> verify(
            @PathVariable Long datasetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return ApiV1Response.ok(service.verify(datasetId, requestId(requestId)));
    }

    @PostMapping("/datasets/{datasetId}/activate")
    public ApiV1Response<RegisteredDatasetView> activate(
            @PathVariable Long datasetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return ApiV1Response.ok(service.activate(datasetId, requestId(requestId)));
    }

    @PostMapping("/datasets/{datasetId}/disable")
    public ApiV1Response<RegisteredDatasetView> disable(
            @PathVariable Long datasetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        return ApiV1Response.ok(service.disable(datasetId, requestId(requestId)));
    }

    @GetMapping("/datasets/{datasetId}/replicas")
    public ApiV1Response<List<DatasetReplica>> replicas(@PathVariable Long datasetId) {
        return ApiV1Response.ok(service.listReplicas(datasetId));
    }

    @PostMapping("/datasets/{datasetId}/replicas")
    public ResponseEntity<ApiV1Response<DatasetReplica>> registerReplica(
            @PathVariable Long datasetId,
            @RequestBody RegisterReplicaRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        if (request == null || request.getCandidateId() == null) {
            throw RegistrationException.invalid("candidateId is required");
        }
        DatasetReplica replica = service.addReplica(
                datasetId, request.getCandidateId(), requestId(requestId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiV1Response.ok(replica));
    }

    @PutMapping("/datasets/{datasetId}/runtime-image")
    public ApiV1Response<RegisteredDatasetView> bindRuntimeImage(
            @PathVariable Long datasetId,
            @RequestBody BindRuntimeImageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        if (request == null || request.getRuntimeImageId() == null) {
            throw RegistrationException.invalid("runtimeImageId is required");
        }
        return ApiV1Response.ok(service.bindRuntimeImage(
                datasetId, request.getRuntimeImageId(), requestId(requestId)));
    }

    @DeleteMapping("/datasets/{datasetId}")
    public ApiV1Response<OperationResult> unregister(
            @PathVariable Long datasetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        service.unregister(datasetId, requestId(requestId));
        return ApiV1Response.ok(OperationResult.completed("dataset unregistered"));
    }

    private String requestId(String requestId) {
        return requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString()
                : requestId.trim();
    }
}
