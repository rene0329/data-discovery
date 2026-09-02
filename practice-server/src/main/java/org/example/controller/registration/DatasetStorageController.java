package org.example.controller.registration;

import org.example.dto.scheduling.DatasetStoragePlan;
import org.example.dto.scheduling.SchedulingPlanAccepted;
import org.example.service.ApiIdempotencyService;
import org.example.service.DatasetHeatService;
import org.example.service.DatasetStorageService;
import org.example.exception.RegistrationException;
import org.example.vo.ApiV1Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DatasetStorageController {
    private final DatasetStorageService storage;
    private final DatasetHeatService heat;
    private final ApiIdempotencyService idempotency;

    public DatasetStorageController(DatasetStorageService storage, DatasetHeatService heat,
                                    ApiIdempotencyService idempotency) {
        this.storage = storage;
        this.heat = heat;
        this.idempotency = idempotency;
    }

    @GetMapping("/datasets/storage-policy")
    public ApiV1Response<Map<String, Object>> policy() { return ApiV1Response.ok(storage.policy()); }

    @PostMapping("/datasets/heat-refresh")
    public ApiV1Response<Map<String, Integer>> refreshHeat() {
        return ApiV1Response.ok(Collections.singletonMap("updatedCount", heat.refresh()));
    }

    @PostMapping("/scheduling/storage-plans/preview")
    public ApiV1Response<DatasetStoragePlan> preview(@RequestParam String mode) {
        return ApiV1Response.ok(storage.preview(mode));
    }

    @PostMapping("/scheduling/storage-plans")
    public ResponseEntity<ApiV1Response<SchedulingPlanAccepted>> submit(@RequestBody DatasetStoragePlan.Submit request) {
        if (request == null) throw RegistrationException.invalid("request body is required");
        SchedulingPlanAccepted accepted = idempotency.execute("DATASET_STORAGE", "SUBMIT",
                request.getExternalPlanId(), request.getMode(), request, SchedulingPlanAccepted.class,
                () -> storage.submit(request), result -> String.valueOf(result.getPlanId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiV1Response.ok(accepted));
    }
}
