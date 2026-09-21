package org.example.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AuthService {
    private final AuthMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final CurrentUserService currentUsers;
    private final ImpersonationAuditMapper impersonationAudit;

    @Autowired
    public AuthService(AuthMapper mapper, PasswordEncoder passwordEncoder,
                       JwtTokenService tokens, CurrentUserService currentUsers,
                       ImpersonationAuditMapper impersonationAudit) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.currentUsers = currentUsers;
        this.impersonationAudit = impersonationAudit;
    }

    AuthService(AuthMapper mapper, PasswordEncoder passwordEncoder,
                JwtTokenService tokens, CurrentUserService currentUsers) {
        this(mapper, passwordEncoder, tokens, currentUsers, null);
    }

    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        String username = request == null ? null : trim(request.getUsername());
        String password = request == null ? null : request.getPassword();
        if (username == null || password == null) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "LOGIN_REQUIRED", "username and password are required");
        }
        AuthUserRecord user = mapper.findUserByUsername(username.toLowerCase(Locale.ROOT));
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "invalid username or password");
        }
        if (user.getDomainId() != null && !Boolean.TRUE.equals(user.getDomainEnabled())) {
            throw new AuthException(HttpStatus.FORBIDDEN, "DOMAIN_DISABLED", "the user's domain is disabled");
        }
        List<String> roles = mapper.listRoleCodes(user.getUserId());
        if (roles.isEmpty()) {
            throw new AuthException(HttpStatus.FORBIDDEN, "NO_ROLE", "the user has no assigned role");
        }
        return new AuthDtos.LoginResponse(tokens.create(user, roles));
    }

    public AuthDtos.MeResponse me() {
        return new AuthDtos.MeResponse(currentUsers.currentUser());
    }

    public AuthDtos.LoginResponse impersonate(AuthDtos.ImpersonationRequest request, String clientIp) {
        AuthenticatedUser actorPrincipal = currentUsers.requireRole("ADMIN");
        if (actorPrincipal.isImpersonated()) {
            throw new AuthException(HttpStatus.FORBIDDEN, "IMPERSONATION_NESTED",
                    "nested user switching is not allowed");
        }
        if (request == null || request.getUserId() == null) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "IMPERSONATION_TARGET_REQUIRED",
                    "target user is required");
        }
        AuthUserRecord actor = mapper.findUserById(actorPrincipal.getUserId());
        AuthUserRecord target = mapper.findUserById(request.getUserId());
        if (target == null || !Boolean.TRUE.equals(target.getEnabled())) {
            throw new AuthException(HttpStatus.NOT_FOUND, "IMPERSONATION_TARGET_UNAVAILABLE",
                    "target user is unavailable");
        }
        if (target.getDomainId() != null && !Boolean.TRUE.equals(target.getDomainEnabled())) {
            throw new AuthException(HttpStatus.CONFLICT, "IMPERSONATION_TARGET_DOMAIN_DISABLED",
                    "target user's domain is disabled");
        }
        List<String> roles = mapper.listRoleCodes(target.getUserId());
        if (roles.isEmpty() || roles.contains("ADMIN")) {
            throw new AuthException(HttpStatus.FORBIDDEN, "IMPERSONATION_TARGET_FORBIDDEN",
                    "administrators can switch only to ordinary users");
        }
        String token = tokens.createImpersonated(target, roles, actor);
        audit(actor, target, "START", clientIp);
        return new AuthDtos.LoginResponse(token);
    }

    public AuthDtos.LoginResponse exitImpersonation(String clientIp) {
        AuthenticatedUser effective = currentUsers.require();
        if (!effective.isImpersonated() || effective.getActorUserId() == null) {
            throw new AuthException(HttpStatus.CONFLICT, "IMPERSONATION_NOT_ACTIVE",
                    "user switching is not active");
        }
        AuthUserRecord actor = mapper.findUserById(effective.getActorUserId());
        if (actor == null || !Boolean.TRUE.equals(actor.getEnabled())) {
            throw new AuthException(HttpStatus.FORBIDDEN, "IMPERSONATION_ACTOR_UNAVAILABLE",
                    "administrator account is unavailable");
        }
        List<String> roles = mapper.listRoleCodes(actor.getUserId());
        if (!roles.contains("ADMIN")) {
            throw new AuthException(HttpStatus.FORBIDDEN, "IMPERSONATION_ACTOR_FORBIDDEN",
                    "administrator role is no longer assigned");
        }
        AuthUserRecord target = mapper.findUserById(effective.getUserId());
        audit(actor, target, "EXIT", clientIp);
        return new AuthDtos.LoginResponse(tokens.create(actor, roles));
    }

    AuthenticatedUser authenticatedUser(AuthUserRecord user) {
        Set<String> roles = new LinkedHashSet<>(mapper.listRoleCodes(user.getUserId()));
        return new AuthenticatedUser(user.getUserId(), user.getUsername(), user.getDisplayName(), roles,
                user.getDomainId(), user.getDomainCode(), user.getDomainName());
    }

    AuthenticatedUser authenticatedUser(AuthUserRecord user, AuthUserRecord actor) {
        Set<String> roles = new LinkedHashSet<>(mapper.listRoleCodes(user.getUserId()));
        return new AuthenticatedUser(user.getUserId(), user.getUsername(), user.getDisplayName(), roles,
                user.getDomainId(), user.getDomainCode(), user.getDomainName(), true,
                actor.getUserId(), actor.getUsername());
    }

    private void audit(AuthUserRecord actor, AuthUserRecord target, String action, String clientIp) {
        if (impersonationAudit != null && actor != null && target != null) {
            impersonationAudit.insert(actor.getUserId(), actor.getUsername(), target.getUserId(),
                    target.getUsername(), action, clientIp);
        }
    }

    private String trim(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}
