package org.example.auth;

import org.example.dto.registration.RegisteredDatasetView;
import org.example.vo.ApiV1Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminIdentityController {
    private final AdminIdentityService service;

    public AdminIdentityController(AdminIdentityService service) { this.service = service; }

    @GetMapping("/domains")
    public ApiV1Response<List<CollaborationDomain>> domains() {
        return ApiV1Response.ok(service.listDomains());
    }

    @PostMapping("/domains")
    public ResponseEntity<ApiV1Response<CollaborationDomain>> createDomain(
            @RequestBody AuthDtos.DomainRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiV1Response.ok(service.createDomain(request)));
    }

    @PatchMapping("/domains/{id}")
    public ApiV1Response<CollaborationDomain> updateDomain(@PathVariable Long id,
                                                            @RequestBody AuthDtos.DomainRequest request) {
        return ApiV1Response.ok(service.updateDomain(id, request));
    }

    @GetMapping("/users")
    public ApiV1Response<List<AuthDtos.UserView>> users() {
        return ApiV1Response.ok(service.listUsers());
    }

    @PostMapping("/users")
    public ResponseEntity<ApiV1Response<AuthDtos.UserView>> createUser(
            @RequestBody AuthDtos.UserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiV1Response.ok(service.createUser(request)));
    }

    @PatchMapping("/users/{id}")
    public ApiV1Response<AuthDtos.UserView> updateUser(@PathVariable Long id,
                                                       @RequestBody AuthDtos.UserRequest request) {
        return ApiV1Response.ok(service.updateUser(id, request));
    }

    @PostMapping("/users/{id}/reset-password")
    public ApiV1Response<AuthDtos.UserView> resetPassword(@PathVariable Long id,
                                                           @RequestBody AuthDtos.ResetPasswordRequest request) {
        return ApiV1Response.ok(service.resetPassword(id, request));
    }

    @PutMapping("/datasets/{id}/owner")
    public ApiV1Response<RegisteredDatasetView> assignDatasetOwner(
            @PathVariable Long id, @RequestBody AuthDtos.DatasetOwnerRequest request) {
        return ApiV1Response.ok(service.assignDatasetOwner(id, request));
    }
}
