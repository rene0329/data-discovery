package org.example.controller.registration;

import org.example.access.DatasetAccessEvent;
import org.example.access.DatasetAccessRequest;
import org.example.access.DatasetAccessService;
import org.example.vo.ApiV1Response;
import org.example.security.access.AccessAuthorizationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class DatasetAccessController {
    private final DatasetAccessService access;
    public DatasetAccessController(DatasetAccessService access) { this.access = access; }

    @PostMapping("/datasets/{datasetId}/access-tests")
    public ResponseEntity<ApiV1Response<DatasetAccessEvent>> read(
            @PathVariable Long datasetId, @RequestBody DatasetAccessRequest request,
            @RequestHeader(value = "X-Dataset-Authorization", required = false) String authorization,
            HttpServletRequest servletRequest) {
        try {
            return ResponseEntity.ok(ApiV1Response.ok(access.read(datasetId, request, authorization,
                    servletRequest == null ? null : servletRequest.getRemoteAddr())));
        } catch (AccessAuthorizationException error) {
            return ResponseEntity.status(error.getStatus()).body(ApiV1Response.error(
                    error.getStatus().value(), error.getErrorCode(), error.getMessage()));
        }
    }

    @GetMapping("/dataset-access-events")
    public ApiV1Response<List<DatasetAccessEvent>> events(
            @RequestParam(required = false) Long datasetId,
            @RequestParam(required = false) String runId,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiV1Response.ok(access.list(datasetId, runId, limit));
    }
}
