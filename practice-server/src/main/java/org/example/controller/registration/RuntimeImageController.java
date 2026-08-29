package org.example.controller.registration;

import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisterRuntimeImageRequest;
import org.example.dto.registration.RuntimeImageView;
import org.example.dto.registration.UpdateRuntimeImageRequest;
import org.example.service.RuntimeImageRegistrationService;
import org.example.service.ApiIdempotencyService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runtime-images")
public class RuntimeImageController {
    private final RuntimeImageRegistrationService service;
    private final ApiIdempotencyService idempotency;

    public RuntimeImageController(RuntimeImageRegistrationService service,
                                  ApiIdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @GetMapping
    public ApiV1Response<PageResult<RuntimeImageView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String status) {
        return ApiV1Response.ok(PageResult.of(service.list(query, status), page, pageSize));
    }

    @PostMapping
    public ResponseEntity<ApiV1Response<RuntimeImageView>> register(
            @RequestBody RegisterRuntimeImageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        RuntimeImageView image = idempotency.execute("RUNTIME_IMAGE", "REGISTER", id,
                null, request, RuntimeImageView.class, () -> service.register(request, id),
                item -> String.valueOf(item.getImageId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiV1Response.ok(image));
    }

    @GetMapping("/{imageId}")
    public ApiV1Response<RuntimeImageView> get(@PathVariable Long imageId) {
        return ApiV1Response.ok(service.get(imageId));
    }

    @PatchMapping("/{imageId}")
    public ApiV1Response<RuntimeImageView> update(
            @PathVariable Long imageId,
            @RequestBody UpdateRuntimeImageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        RuntimeImageView image = idempotency.execute("RUNTIME_IMAGE", "UPDATE", id,
                String.valueOf(imageId), request, RuntimeImageView.class,
                () -> service.update(imageId, request, id), item -> String.valueOf(item.getImageId()));
        return ApiV1Response.ok(image);
    }

    @PostMapping("/{imageId}/verify")
    public ApiV1Response<RuntimeImageView> verify(
            @PathVariable Long imageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        RuntimeImageView image = idempotency.execute("RUNTIME_IMAGE", "VERIFY", id,
                String.valueOf(imageId), null, RuntimeImageView.class,
                () -> service.verify(imageId, id), item -> String.valueOf(item.getImageId()));
        return ApiV1Response.ok(image);
    }

    @PostMapping("/{imageId}/activate")
    public ApiV1Response<RuntimeImageView> activate(
            @PathVariable Long imageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        RuntimeImageView image = idempotency.execute("RUNTIME_IMAGE", "ACTIVATE", id,
                String.valueOf(imageId), null, RuntimeImageView.class,
                () -> service.activate(imageId, id), item -> String.valueOf(item.getImageId()));
        return ApiV1Response.ok(image);
    }

    @PostMapping("/{imageId}/disable")
    public ApiV1Response<RuntimeImageView> disable(
            @PathVariable Long imageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        RuntimeImageView image = idempotency.execute("RUNTIME_IMAGE", "DISABLE", id,
                String.valueOf(imageId), null, RuntimeImageView.class,
                () -> service.disable(imageId, id), item -> String.valueOf(item.getImageId()));
        return ApiV1Response.ok(image);
    }

    @DeleteMapping("/{imageId}")
    public ApiV1Response<OperationResult> unregister(
            @PathVariable Long imageId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId(requestId);
        OperationResult result = idempotency.execute("RUNTIME_IMAGE", "UNREGISTER", id,
                String.valueOf(imageId), null, OperationResult.class, () -> {
                    service.unregister(imageId, id);
                    return OperationResult.completed("runtime image unregistered");
                }, item -> String.valueOf(imageId));
        return ApiV1Response.ok(result);
    }

    private String requestId(String requestId) {
        return requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString()
                : requestId.trim();
    }
}
