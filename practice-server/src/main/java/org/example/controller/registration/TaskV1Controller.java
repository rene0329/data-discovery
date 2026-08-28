package org.example.controller.registration;

import org.example.dto.registration.CreateTaskRequest;
import org.example.dto.registration.TaskCreated;
import org.example.service.TaskV1Service;
import org.example.vo.ApiV1Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskV1Controller {
    private final TaskV1Service service;

    public TaskV1Controller(TaskV1Service service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiV1Response<TaskCreated>> create(
            @RequestBody CreateTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId.trim();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiV1Response.ok(service.create(request, id)));
    }
}
