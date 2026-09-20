package org.example.auth;

import org.example.vo.ApiV1Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/login")
    public ApiV1Response<AuthDtos.LoginResponse> login(@RequestBody AuthDtos.LoginRequest request) {
        return ApiV1Response.ok(service.login(request));
    }

    @GetMapping("/me")
    public ApiV1Response<AuthDtos.MeResponse> me() {
        return ApiV1Response.ok(service.me());
    }
}
