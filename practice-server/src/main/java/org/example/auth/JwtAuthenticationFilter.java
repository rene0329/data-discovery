package org.example.auth;

import io.jsonwebtoken.Claims;
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
import java.util.stream.Collectors;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenService tokens;
    private final AuthMapper mapper;
    private final AuthService authService;

    public JwtAuthenticationFilter(JwtTokenService tokens, AuthMapper mapper, AuthService authService) {
        this.tokens = tokens; this.mapper = mapper; this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            try {
                Claims claims = tokens.parse(header.substring(7).trim());
                Long userId = ((Number) claims.get("uid")).longValue();
                int tokenVersion = ((Number) claims.get("ver")).intValue();
                AuthUserRecord user = mapper.findUserById(userId);
                if (user != null && Boolean.TRUE.equals(user.getEnabled())
                        && user.getTokenVersion() != null && user.getTokenVersion() == tokenVersion
                        && (user.getDomainId() == null || Boolean.TRUE.equals(user.getDomainEnabled()))) {
                    AuthenticatedUser principal = resolvePrincipal(claims, user);
                    if (principal == null) {
                        chain.doFilter(request, response);
                        return;
                    }
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getRoles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).collect(Collectors.toList()));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private AuthenticatedUser resolvePrincipal(Claims claims, AuthUserRecord effectiveUser) {
        if (!Boolean.TRUE.equals(claims.get("imp", Boolean.class))) {
            return authService.authenticatedUser(effectiveUser);
        }
        Number actorIdClaim = claims.get("actorUid", Number.class);
        Number actorVersionClaim = claims.get("actorVer", Number.class);
        if (actorIdClaim == null || actorVersionClaim == null) return null;
        AuthUserRecord actor = mapper.findUserById(actorIdClaim.longValue());
        if (actor == null || !Boolean.TRUE.equals(actor.getEnabled()) || actor.getTokenVersion() == null
                || actor.getTokenVersion().intValue() != actorVersionClaim.intValue()
                || !mapper.listRoleCodes(actor.getUserId()).contains("ADMIN")) {
            return null;
        }
        return authService.authenticatedUser(effectiveUser, actor);
    }
}
