package org.example.controller.registration;

import org.example.dto.scheduling.SchedulableDatasetView;
import org.example.dto.scheduling.SchedulingPageResult;
import org.example.dto.scheduling.SchedulingPlanAccepted;
import org.example.dto.scheduling.SchedulingPlanRequest;
import org.example.service.SchedulingService;
import org.example.vo.ApiV1Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scheduling")
public class SchedulingController {
    private final SchedulingService service;

    public SchedulingController(SchedulingService service) {
        this.service = service;
    }

    @GetMapping("/datasets")
    public ApiV1Response<SchedulingPageResult<SchedulableDatasetView>> datasets(
            @RequestParam(required = false) String datasetIds,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) Integer nodeId,
            @RequestParam(required = false) String label,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return success(service.listDatasets(
                datasetIds, category, format, nodeId, label, page, pageSize));
    }

    @PostMapping("/plans")
    public ResponseEntity<ApiV1Response<SchedulingPlanAccepted>> submit(
            @RequestBody SchedulingPlanRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(success(service.submit(request)));
    }

    private <T> ApiV1Response<T> success(T data) {
        ApiV1Response<T> response = ApiV1Response.ok(data);
        response.setMsg("success");
        return response;
    }
}
