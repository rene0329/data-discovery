package org.example.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AuthService {
    private final AuthMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final CurrentUserService currentUsers;

    public AuthService(AuthMapper mapper, PasswordEncoder passwordEncoder,
                       JwtTokenService tokens, CurrentUserService currentUsers) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.currentUsers = currentUsers;
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

    AuthenticatedUser authenticatedUser(AuthUserRecord user) {
        Set<String> roles = new LinkedHashSet<>(mapper.listRoleCodes(user.getUserId()));
        return new AuthenticatedUser(user.getUserId(), user.getUsername(), user.getDisplayName(), roles,
                user.getDomainId(), user.getDomainCode(), user.getDomainName());
    }

    private String trim(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}
