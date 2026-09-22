package org.example.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.vo.ApiV1Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

@Component
public class InternalAgentAuthenticationFilter extends OncePerRequestFilter {
    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    public InternalAgentAuthenticationFilter(
            @Value("${app.auth.internal-agent-token:}") String token,
            ObjectMapper objectMapper) {
        this.expectedToken = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getRequestURI();
        return !("/api/network/metrics/batch".equals(path)
                || "/api/network/nodes/public-ip".equals(path)
                || "/api/network/nodes/heartbeat".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        byte[] actual = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (expectedToken.length < 32 || !MessageDigest.isEqual(expectedToken, actual)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    ApiV1Response.error(401, "INTERNAL_AGENT_AUTH_FAILED",
                            "valid internal agent credentials are required"));
            return;
        }
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "internal-agent", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_INTERNAL_AGENT")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
