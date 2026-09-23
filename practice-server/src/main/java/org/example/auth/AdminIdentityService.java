package org.example.auth;

import org.example.dto.registration.RegisteredDatasetView;
import org.example.service.DatasetRegistrationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AdminIdentityService {
    private static final Pattern CODE = Pattern.compile("^[a-zA-Z0-9._-]{2,64}$");
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9._-]{3,64}$");
    private static final Pattern SITE_CODE = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final String SITE_UNIQUE_KEY = "uk_collaboration_domain_site";
    private static final Set<String> ALLOWED_ROLES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("ADMIN", "DATA_OWNER", "AUDITOR")));

    private final AuthMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final DatasetRegistrationService datasets;

    public AdminIdentityService(AuthMapper mapper, PasswordEncoder passwordEncoder,
                                DatasetRegistrationService datasets) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.datasets = datasets;
    }

    public List<CollaborationDomain> listDomains() { return mapper.listDomains(); }

    @Transactional
    public CollaborationDomain createDomain(AuthDtos.DomainRequest request) {
        String code = required(request == null ? null : request.getCode(), "domain code").toLowerCase(Locale.ROOT);
        String name = required(request.getName(), "domain name");
        if (!CODE.matcher(code).matches()) invalid("domain code must contain 2-64 letters, digits, dots, '_' or '-'");
        if (name.length() > 128) invalid("domain name is too long");
        String siteCode = normalizeSiteCode(request.getSiteCode());
        CollaborationDomain domain = new CollaborationDomain();
        domain.setCode(code); domain.setName(name); domain.setSiteCode(siteCode);
        domain.setDescription(trim(request.getDescription()));
        domain.setEnabled(request.getEnabled() == null || request.getEnabled());
        requireSiteFree(siteCode, null);
        try {
            mapper.insertDomain(domain);
        } catch (DuplicateKeyException ex) {
            if (violates(ex, SITE_UNIQUE_KEY)) throw siteTaken(siteCode, null);
            throw new AuthException(HttpStatus.CONFLICT, "DOMAIN_CODE_EXISTS", "domain code already exists");
        }
        return mapper.findDomainById(domain.getId());
    }

    @Transactional
    public CollaborationDomain updateDomain(Long id, AuthDtos.DomainRequest request) {
        CollaborationDomain domain = requireDomain(id);
        if (request == null) invalid("request body is required");
        if (request.getCode() != null && !domain.getCode().equalsIgnoreCase(request.getCode().trim())) {
            invalid("domain code cannot be changed");
        }
        if (request.getName() != null) domain.setName(required(request.getName(), "domain name"));
        if (request.getDescription() != null) domain.setDescription(trim(request.getDescription()));
        if (request.getEnabled() != null) domain.setEnabled(request.getEnabled());
        if (request.hasSiteCode()) {
            String siteCode = normalizeSiteCode(request.getSiteCode());
            requireSiteFree(siteCode, domain.getId());
            domain.setSiteCode(siteCode);
        }
        try {
            mapper.updateDomain(domain);
        } catch (DuplicateKeyException ex) {
            // site_code is the only unique column an update can change (the code is immutable).
            throw siteTaken(domain.getSiteCode(), null);
        }
        return mapper.findDomainById(id);
    }

    public List<AuthDtos.UserView> listUsers() {
        return mapper.listUsers().stream().map(this::view).collect(Collectors.toList());
    }

    @Transactional
    public AuthDtos.UserView createUser(AuthDtos.UserRequest request) {
        if (request == null) invalid("request body is required");
        String username = required(request.getUsername(), "username").toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) invalid("username must contain 3-64 letters, digits, dots, '_' or '-'");
        validatePassword(request.getPassword());
        Set<String> roles = normalizeRoles(request.getRoles());
        CollaborationDomain domain = validateDomainForRoles(request.getDomainId(), roles);
        AuthUserRecord user = new AuthUserRecord();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(required(request.getDisplayName(), "display name"));
        user.setDomainId(domain == null ? null : domain.getId());
        user.setEnabled(request.getEnabled() == null || request.getEnabled());
        user.setTokenVersion(0);
        try {
            mapper.insertUser(user);
        } catch (DuplicateKeyException ex) {
            throw new AuthException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "username already exists");
        }
        replaceRoles(user.getUserId(), roles);
        return view(mapper.findUserById(user.getUserId()));
    }

    @Transactional
    public AuthDtos.UserView updateUser(Long id, AuthDtos.UserRequest request) {
        AuthUserRecord user = requireUser(id);
        if (request == null) invalid("request body is required");
        if (request.getUsername() != null && !user.getUsername().equalsIgnoreCase(request.getUsername().trim())) {
            invalid("username cannot be changed");
        }
        Set<String> roles = request.getRoles() == null
                ? new LinkedHashSet<>(mapper.listRoleCodes(id)) : normalizeRoles(request.getRoles());
        Long domainId = request.getDomainId() == null ? user.getDomainId() : request.getDomainId();
        CollaborationDomain domain = validateDomainForRoles(domainId, roles);
        boolean ownershipBindingChanged = !roles.contains("DATA_OWNER")
                || !java.util.Objects.equals(user.getDomainId(), domain == null ? null : domain.getId());
        if (ownershipBindingChanged && mapper.countOwnedDatasets(id) > 0) {
            throw new AuthException(HttpStatus.CONFLICT, "DATASET_OWNERSHIP_EXISTS",
                    "reassign the user's datasets before changing its DATA_OWNER role or domain");
        }
        if (request.getDisplayName() != null) user.setDisplayName(required(request.getDisplayName(), "display name"));
        user.setDomainId(domain == null ? null : domain.getId());
        if (request.getEnabled() != null) user.setEnabled(request.getEnabled());
        mapper.updateUser(user);
        if (request.getRoles() != null) replaceRoles(id, roles);
        return view(mapper.findUserById(id));
    }

    @Transactional
    public AuthDtos.UserView resetPassword(Long id, AuthDtos.ResetPasswordRequest request) {
        requireUser(id);
        String password = request == null ? null : request.getPassword();
        validatePassword(password);
        mapper.resetPassword(id, passwordEncoder.encode(password));
        return view(mapper.findUserById(id));
    }

    @Transactional
    public RegisteredDatasetView assignDatasetOwner(Long datasetId, AuthDtos.DatasetOwnerRequest request) {
        if (request == null || request.getUserId() == null) invalid("userId is required");
        AuthUserRecord owner = requireUser(request.getUserId());
        Set<String> roles = new LinkedHashSet<>(mapper.listRoleCodes(owner.getUserId()));
        if (!Boolean.TRUE.equals(owner.getEnabled()) || !roles.contains("DATA_OWNER")) {
            throw new AuthException(HttpStatus.CONFLICT, "INVALID_DATA_OWNER",
                    "dataset owner must be an enabled DATA_OWNER user");
        }
        CollaborationDomain domain = validateDomainForRoles(owner.getDomainId(), roles);
        if (mapper.assignDatasetOwner(datasetId, owner.getUserId(), domain.getId()) != 1) {
            throw new AuthException(HttpStatus.NOT_FOUND, "DATASET_NOT_FOUND", "dataset was not found");
        }
        return datasets.getDataset(datasetId);
    }

    @Transactional
    public void bootstrapAdmin(String username, String password, String displayName) {
        if (trim(username) == null || password == null || password.isEmpty()) return;
        AuthUserRecord existing = mapper.findUserByUsername(username.trim().toLowerCase(Locale.ROOT));
        if (existing != null) return;
        AuthDtos.UserRequest request = new AuthDtos.UserRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setDisplayName(trim(displayName) == null ? username : displayName.trim());
        request.setRoles(Collections.singleton("ADMIN"));
        createUser(request);
    }

    private AuthDtos.UserView view(AuthUserRecord user) {
        return new AuthDtos.UserView(user, new LinkedHashSet<>(mapper.listRoleCodes(user.getUserId())));
    }

    private void replaceRoles(Long userId, Set<String> roles) {
        mapper.deleteUserRoles(userId);
        for (String role : roles) mapper.insertUserRole(userId, role);
    }

    private Set<String> normalizeRoles(Set<String> input) {
        if (input == null || input.isEmpty()) invalid("at least one role is required");
        Set<String> roles = input.stream().filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (roles.isEmpty() || !ALLOWED_ROLES.containsAll(roles)) invalid("roles may only contain ADMIN, DATA_OWNER or AUDITOR");
        return roles;
    }

    private CollaborationDomain validateDomainForRoles(Long domainId, Set<String> roles) {
        if (domainId == null) {
            if (roles.contains("DATA_OWNER")) invalid("DATA_OWNER must belong to a domain");
            return null;
        }
        CollaborationDomain domain = requireDomain(domainId);
        if (!Boolean.TRUE.equals(domain.getEnabled())) {
            throw new AuthException(HttpStatus.CONFLICT, "DOMAIN_DISABLED", "the selected domain is disabled");
        }
        return domain;
    }

    /** Blank clears the site; otherwise trimmed, lower-cased and checked like node site codes. */
    private String normalizeSiteCode(String value) {
        String site = trim(value);
        if (site == null) return null;
        site = site.toLowerCase(Locale.ROOT);
        if (!SITE_CODE.matcher(site).matches()) invalid("site code must contain 1-32 lower-case letters, digits or '-'");
        return site;
    }

    /** One domain per site: a site already mapped to another domain is a conflict. */
    private void requireSiteFree(String siteCode, Long domainId) {
        if (siteCode == null) return;
        CollaborationDomain holder = mapper.findDomainBySiteCode(siteCode);
        if (holder != null && !holder.getId().equals(domainId)) throw siteTaken(siteCode, holder);
    }

    private AuthException siteTaken(String siteCode, CollaborationDomain holder) {
        return new AuthException(HttpStatus.CONFLICT, "DOMAIN_SITE_TAKEN", "site '" + siteCode
                + "' is already mapped to " + (holder == null ? "another domain" : "domain '" + holder.getCode() + "'"));
    }

    private static boolean violates(DuplicateKeyException ex, String key) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(key)) return true;
        }
        return false;
    }

    private AuthUserRecord requireUser(Long id) {
        AuthUserRecord user = id == null ? null : mapper.findUserById(id);
        if (user == null) throw new AuthException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "user was not found");
        return user;
    }

    private CollaborationDomain requireDomain(Long id) {
        CollaborationDomain domain = id == null ? null : mapper.findDomainById(id);
        if (domain == null) throw new AuthException(HttpStatus.NOT_FOUND, "DOMAIN_NOT_FOUND", "domain was not found");
        return domain;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 128) {
            invalid("password must contain 10-128 characters");
        }
    }

    private String required(String value, String label) {
        String result = trim(value);
        if (result == null) invalid(label + " is required");
        return result;
    }

    private String trim(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private void invalid(String message) { throw new AuthException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message); }
}
