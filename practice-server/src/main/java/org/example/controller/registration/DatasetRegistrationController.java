package org.example.controller.registration;

import org.example.dto.registration.BindRuntimeImageRequest;
import org.example.dto.registration.DatasetDiscoveryRequest;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisterDatasetRequest;
import org.example.dto.registration.RegisterReplicaRequest;
import org.example.dto.registration.RegisteredDatasetView;
import org.example.dto.registration.UpdateDatasetRequest;
import org.example.dto.registration.UploadDatasetRequest;
import org.example.entity.DatasetDiscoveryCandidate;
import org.example.entity.DatasetReplica;
import org.example.exception.RegistrationException;
import org.example.service.DatasetRegistrationService;
import org.example.service.ApiIdempotencyService;
import org.example.vo.ApiV1Response;
import org.example.vo.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DatasetRegistrationController {
    private final DatasetRegistrationService service;
    private final ApiIdempotencyService idempotency;

    public DatasetRegistrationController(DatasetRegistrationService service,
                                         ApiIdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping("/dataset-discovery-runs")
    public ResponseEntity<ApiV1Response<OperationResult>> discover(
            @RequestBody(required = false) DatasetDiscoveryRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        OperationResult result = idempotency.execute("DATASET", "DISCOVER", id, null, request,
                OperationResult.class,
                () -> service.discover(request == null ? Collections.emptySet() : request.getNodeIds()),
                OperationResult::getOperationId);
        return ResponseEntity.ok(ApiV1Response.ok(result));
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
        String id = requestId(requestId);
        RegisteredDatasetView dataset = idempotency.execute("DATASET", "REGISTER", id,
                null, request, RegisteredDatasetView.class, () -> service.register(request, id),
                item -> String.valueOf(item.getDatasetId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiV1Response.ok(dataset));
    }

    @PostMapping(value = "/datasets/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiV1Response<RegisteredDatasetView>> uploadAndRegister(
            @RequestPart("metadata") UploadDatasetRequest request,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        String fileIdentity = (request == null ? "" : String.valueOf(request.getNodeId())) + "|"
                + (file == null ? "" : String.valueOf(file.getOriginalFilename())) + "|"
                + (file == null ? 0L : file.getSize());
        RegisteredDatasetView dataset = idempotency.execute("DATASET", "UPLOAD_REGISTER", id,
                fileIdentity, request, RegisteredDatasetView.class,
                () -> service.uploadAndRegister(request, file, id),
                item -> String.valueOf(item.getDatasetId()));
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
        String id = requestId(requestId);
        RegisteredDatasetView dataset = idempotency.execute("DATASET", "UPDATE", id,
                String.valueOf(datasetId), request, RegisteredDatasetView.class,
                () -> service.update(datasetId, request, id),
                item -> String.valueOf(item.getDatasetId()));
        return ApiV1Response.ok(dataset);
    }

    @PostMapping("/datasets/{datasetId}/verify")
    public ApiV1Response<RegisteredDatasetView> verify(
            @PathVariable Long datasetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        RegisteredDatasetView dataset = idempotency.execute("DATASET", "VERIFY", id,
                String.valueOf(datasetId), null, RegisteredDatasetView.class,
                () -> service.verify(datasetId, id), item -> String.valueOf(item.getDatasetId()));
        return ApiV1Response.ok(dataset);
    }

    @PostMapping("/datasets/{datasetId}/activate")
    public ApiV1Response<RegisteredDatasetView> activate(
            @PathVariable Long datasetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        RegisteredDatasetView dataset = idempotency.execute("DATASET", "ACTIVATE", id,
                String.valueOf(datasetId), null, RegisteredDatasetView.class,
                () -> service.activate(datasetId, id), item -> String.valueOf(item.getDatasetId()));
        return ApiV1Response.ok(dataset);
    }

    @PostMapping("/datasets/{datasetId}/disable")
    public ApiV1Response<RegisteredDatasetView> disable(
            @PathVariable Long datasetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        RegisteredDatasetView dataset = idempotency.execute("DATASET", "DISABLE", id,
                String.valueOf(datasetId), null, RegisteredDatasetView.class,
                () -> service.disable(datasetId, id), item -> String.valueOf(item.getDatasetId()));
        return ApiV1Response.ok(dataset);
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
        String id = requestId(requestId);
        DatasetReplica replica = idempotency.execute("DATASET", "ADD_REPLICA", id,
                String.valueOf(datasetId), request, DatasetReplica.class,
                () -> service.addReplica(datasetId, request.getCandidateId(), id),
                item -> String.valueOf(item.getReplicaId()));
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
        String id = requestId(requestId);
        RegisteredDatasetView dataset = idempotency.execute("DATASET", "BIND_IMAGE", id,
                String.valueOf(datasetId), request, RegisteredDatasetView.class,
                () -> service.bindRuntimeImage(datasetId, request.getRuntimeImageId(), id),
                item -> String.valueOf(item.getDatasetId()));
        return ApiV1Response.ok(dataset);
    }

    @DeleteMapping("/datasets/{datasetId}")
    public ApiV1Response<OperationResult> unregister(
            @PathVariable Long datasetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        OperationResult result = idempotency.execute("DATASET", "UNREGISTER", id,
                String.valueOf(datasetId), null, OperationResult.class, () -> {
                    service.unregister(datasetId, id);
                    return OperationResult.completed("dataset unregistered");
                }, item -> String.valueOf(datasetId));
        return ApiV1Response.ok(result);
    }

    private String requestId(String requestId) {
        return requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString()
                : requestId.trim();
    }
}
