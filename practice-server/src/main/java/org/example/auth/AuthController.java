package org.example.auth;

import org.example.vo.ApiV1Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final boolean directLoginEnabled;
    private final String directLoginUsername;

    public AuthController(AuthService service,
                          @Value("${app.auth.direct-login.enabled:false}") boolean directLoginEnabled,
                          @Value("${app.auth.direct-login.username:}") String directLoginUsername) {
        this.service = service;
        this.directLoginEnabled = directLoginEnabled;
        this.directLoginUsername = directLoginUsername;
    }

    @PostMapping("/login")
    public ApiV1Response<AuthDtos.LoginResponse> login(@RequestBody AuthDtos.LoginRequest request) {
        return ApiV1Response.ok(service.login(request));
    }

    @PostMapping("/direct-login")
    public ApiV1Response<AuthDtos.LoginResponse> directLogin() {
        if (!directLoginEnabled) {
            throw new AuthException(HttpStatus.NOT_FOUND, "DIRECT_LOGIN_DISABLED",
                    "direct login is disabled");
        }
        return ApiV1Response.ok(service.directLogin(directLoginUsername));
    }

    @GetMapping("/me")
    public ApiV1Response<AuthDtos.MeResponse> me() {
        return ApiV1Response.ok(service.me());
    }

    @PostMapping("/impersonation")
    public ApiV1Response<AuthDtos.LoginResponse> impersonate(
            @RequestBody AuthDtos.ImpersonationRequest request, HttpServletRequest httpRequest) {
        return ApiV1Response.ok(service.impersonate(request, clientIp(httpRequest)));
    }

    @PostMapping("/impersonation/exit")
    public ApiV1Response<AuthDtos.LoginResponse> exitImpersonation(HttpServletRequest httpRequest) {
        return ApiV1Response.ok(service.exitImpersonation(clientIp(httpRequest)));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
