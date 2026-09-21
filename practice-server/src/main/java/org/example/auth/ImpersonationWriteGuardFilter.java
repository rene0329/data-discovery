package org.example.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.vo.ApiV1Response;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class ImpersonationWriteGuardFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;

    public ImpersonationWriteGuardFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AuthenticatedUser user = currentUser();
        if (user != null && user.isImpersonated() && isForbidden(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiV1Response.error(403,
                    "IMPERSONATION_READ_ONLY", "administrator user switching is read-only"));
            return;
        }
        chain.doFilter(request, response);
    }

    private AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser)) return null;
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private boolean isForbidden(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/auth/impersonation/exit".equals(path)) return false;
        if (!"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod())
                && !"OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        return path.matches("/api/v1/privacy-computing/jobs/[^/]+/result")
                || path.matches("/api/v1/security/secure-aggregation/runs/[^/]+/result");
    }
}
