package org.example.controller.admin;

import org.example.dto.admin.DataTransferRateLimitDtos;
import org.example.service.DataTransferRateLimitService;
import org.example.vo.ApiV1Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/rate-limits")
public class DataTransferRateLimitController {
    private final DataTransferRateLimitService service;

    public DataTransferRateLimitController(DataTransferRateLimitService service) {
        this.service = service;
    }

    @GetMapping
    public ApiV1Response<DataTransferRateLimitDtos.View> current() {
        return ApiV1Response.ok(service.current());
    }

    @PutMapping
    public ApiV1Response<DataTransferRateLimitDtos.View> update(
            @RequestBody DataTransferRateLimitDtos.UpdateRequest request) {
        return ApiV1Response.ok(service.update(request));
    }

    @GetMapping("/history")
    public ApiV1Response<List<DataTransferRateLimitDtos.View>> history(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiV1Response.ok(service.history(limit));
    }

    @PostMapping("/rollback")
    public ApiV1Response<DataTransferRateLimitDtos.View> rollback(
            @RequestBody DataTransferRateLimitDtos.RollbackRequest request) {
        return ApiV1Response.ok(service.rollback(request));
    }
}
