package org.example.security.access;

import org.example.vo.ApiV1Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * 访问控制 page: which datasets the signed-in user may use, and self-service
 * short-lived grants for the ones they may not. Errors are rendered by
 * RegistrationExceptionHandler (status + errorCode in the ApiV1Response body).
 */
@RestController
@RequestMapping("/api/v1/security/dataset-access")
public class DatasetUsageController {
    private final DatasetUsagePolicyService service;

    public DatasetUsageController(DatasetUsagePolicyService service) {
        this.service = service;
    }

    @GetMapping
    public ApiV1Response<DatasetUsageModels.Overview> overview() {
        return ApiV1Response.ok(service.overview());
    }

    @PostMapping("/grants")
    public ResponseEntity<ApiV1Response<DatasetUsageModels.GrantIssued>> issueGrant(
            @RequestBody DatasetUsageModels.GrantRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            HttpServletRequest servletRequest) {
        String effectiveRequestId = requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId.trim();
        DatasetUsageModels.GrantIssued issued = service.issueGrant(request, effectiveRequestId,
                servletRequest == null ? null : servletRequest.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiV1Response.ok(issued));
    }
}
