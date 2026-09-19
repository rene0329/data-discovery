package org.example.controller.registration;

import org.example.dto.registration.CreateTaskRequest;
import org.example.dto.registration.TaskCreated;
import org.example.dto.registration.TaskExecutionDetail;
import org.example.dto.registration.TaskPreflightResult;
import org.example.dto.registration.TaskRunComparison;
import org.example.service.TaskV1Service;
import org.example.service.ApiIdempotencyService;
import org.example.vo.ApiV1Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskV1Controller {
    private final TaskV1Service service;
    private final ApiIdempotencyService idempotency;

    public TaskV1Controller(TaskV1Service service, ApiIdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<ApiV1Response<TaskCreated>> create(
            @RequestBody CreateTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId) {
        String id = requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId.trim();
        TaskCreated created = idempotency.execute("TASK", "CREATE", id, null, request,
                TaskCreated.class, () -> service.create(request, id),
                item -> String.valueOf(item.getTaskId()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiV1Response.ok(created));
    }

    @PostMapping("/preflight")
    public ApiV1Response<TaskPreflightResult> preflight(@RequestBody CreateTaskRequest request) {
        return ApiV1Response.ok(service.preflight(request));
    }

    @GetMapping("/{taskId}")
    public ApiV1Response<TaskExecutionDetail> getExecution(@PathVariable Integer taskId) {
        return ApiV1Response.ok(service.getExecution(taskId));
    }

    @GetMapping("/runs/{acceptanceRunId}/comparison")
    public ApiV1Response<TaskRunComparison> compareRun(
            @PathVariable String acceptanceRunId,
            @RequestParam(value = "round", required = false, defaultValue = "1") Integer runRound) {
        return ApiV1Response.ok(service.compareRun(acceptanceRunId, runRound));
    }
}
