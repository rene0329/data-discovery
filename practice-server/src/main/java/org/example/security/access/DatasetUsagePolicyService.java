package org.example.security.access;

import org.example.auth.AuthenticatedUser;
import org.example.auth.CurrentUserService;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which registered datasets the current user may use, issues
 * self-service short-lived usage grants, and records denied task inputs in
 * dataset_access_audit_event (the 异常访问日志 source).
 *
 * <p>Rule, evaluated for the effective (possibly impersonated) user:
 * ADMIN may use every dataset; a DATA_OWNER with a domain may use datasets
 * owned by that domain; anyone may use a dataset for which they hold an
 * unexpired grant. Datasets without an owner domain are reachable by
 * non-admins only through a grant.
 *
 * <p>A request whose principal is not a JWT user (anonymous, internal Agent,
 * background thread) has no roles, no domain and no grants, so every
 * registered dataset is inaccessible to it. The task-input check therefore
 * fails closed with the regular 403 instead of an authentication error.
 */
@Service
public class DatasetUsagePolicyService {
    public static final String BASIS_ADMIN = "ADMIN";
    public static final String BASIS_OWN_DOMAIN = "OWN_DOMAIN";
    public static final String BASIS_GRANT = "GRANT";
    public static final String ACCESS_DENIED_CODE = "DATASET_ACCESS_DENIED";
    public static final String ACCESS_DENIED_MESSAGE = "任务创建失败，用户访问受限";

    static final String ACTION_TASK_CREATE = "TASK_CREATE";
    static final String ACTION_GRANT_ISSUE = "GRANT_ISSUE";
    static final String REASON_ACCESS_DENIED = "CROSS_DOMAIN_ACCESS_DENIED";
    static final String REASON_GRANT_ISSUED = "GRANT_ISSUED";
    static final int MAX_REASON_LENGTH = 500;

    private final CurrentUserService currentUsers;
    private final DatasetAccessGrantMapper grants;
    private final DatasetAccessAuditMapper audits;
    private final DatasetRegistrationMapper datasets;
    private final DatasetAccessProperties properties;
    private final TransactionTemplate independentAuditTransaction;
    private final Clock clock;

    @Autowired
    public DatasetUsagePolicyService(CurrentUserService currentUsers,
                                     DatasetAccessGrantMapper grants,
                                     DatasetAccessAuditMapper audits,
                                     DatasetRegistrationMapper datasets,
                                     DatasetAccessProperties properties,
                                     PlatformTransactionManager transactionManager) {
        this(currentUsers, grants, audits, datasets, properties, transactionManager, Clock.systemUTC());
    }

    DatasetUsagePolicyService(CurrentUserService currentUsers,
                              DatasetAccessGrantMapper grants,
                              DatasetAccessAuditMapper audits,
                              DatasetRegistrationMapper datasets,
                              DatasetAccessProperties properties,
                              PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.currentUsers = currentUsers;
        this.grants = grants;
        this.audits = audits;
        this.datasets = datasets;
        this.properties = properties;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.independentAuditTransaction = template;
        this.clock = clock;
    }

    /**
     * Returns the ids from {@code datasetIds} that the current user may not
     * use, in input order (duplicates and nulls collapsed). Ids that do not
     * identify a registered, non-deleted dataset are not reported here; the
     * caller's own existence validation owns that error. No side effects.
     */
    public List<Long> inaccessibleDatasetIds(Collection<Long> datasetIds) {
        return idsOf(deniedDatasets(currentSubject(), datasetIds, utc(now())));
    }

    /**
     * Same check as {@link #inaccessibleDatasetIds}; additionally writes one
     * DENIED TASK_CREATE row per inaccessible dataset. The rows are written in
     * their own transaction so they survive the caller rolling back the task.
     */
    public List<Long> checkAndAuditTaskDatasets(Collection<Long> datasetIds, String requestId) {
        Subject subject = currentSubject();
        List<RegisteredDataset> denied = deniedDatasets(subject, datasetIds, utc(now()));
        if (!denied.isEmpty()) {
            String clientIp = currentClientIp();
            List<DatasetAccessAuditEvent> events = denied.stream()
                    .map(dataset -> denialEvent(subject, dataset, requestId, clientIp))
                    .collect(Collectors.toList());
            independentAuditTransaction.executeWithoutResult(status -> events.forEach(audits::insert));
        }
        return idsOf(denied);
    }

    /** Fails task creation with 403 DATASET_ACCESS_DENIED when any selected dataset is out of scope. */
    public void requireTaskDatasetsAccessible(Collection<Long> datasetIds, String requestId) {
        if (!checkAndAuditTaskDatasets(datasetIds, requestId).isEmpty()) {
            throw new RegistrationException(HttpStatus.FORBIDDEN, ACCESS_DENIED_CODE, ACCESS_DENIED_MESSAGE);
        }
    }

    /** Every registered dataset with the current user's standing on it. */
    public DatasetUsageModels.Overview overview() {
        Subject subject = Subject.of(currentUsers.require());
        Instant now = now();
        Map<Long, DatasetAccessGrant> active = subject.isAdmin()
                ? Collections.<Long, DatasetAccessGrant>emptyMap()
                : activeGrantsByDataset(subject.userId(), utc(now));
        List<DatasetUsageModels.Item> items = new ArrayList<>();
        for (RegisteredDataset dataset : datasets.listDatasets(null, null)) {
            items.add(item(subject, dataset, active.get(dataset.getDatasetId())));
        }
        return new DatasetUsageModels.Overview(now, ttlMinutes(), items);
    }

    /** Issues a grant valid for the configured TTL, starting immediately; no approval step. */
    @Transactional
    public DatasetUsageModels.GrantIssued issueGrant(DatasetUsageModels.GrantRequest request,
                                                     String requestId, String clientIp) {
        AuthenticatedUser user = currentUsers.require();
        Long datasetId = request == null ? null : request.getDatasetId();
        if (datasetId == null) throw badRequest("datasetId is required");
        String reason = request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isEmpty()) throw badRequest("reason is required");
        if (reason.codePointCount(0, reason.length()) > MAX_REASON_LENGTH) {
            throw badRequest("reason must not exceed " + MAX_REASON_LENGTH + " characters");
        }
        RegisteredDataset dataset = datasets.findDatasetById(datasetId);
        if (dataset == null) {
            throw RegistrationException.notFound("DATASET_NOT_FOUND", "registered dataset not found: " + datasetId);
        }
        Subject subject = Subject.of(user);
        String standing = basis(subject, dataset.getOwnerDomainId(), false);
        if (standing != null) {
            throw RegistrationException.conflict("DATASET_ALREADY_ACCESSIBLE",
                    "dataset is already accessible (" + standing + "); no grant is needed");
        }

        grants.lockUser(user.getUserId());
        LocalDateTime now = utc(now());
        if (!grants.findActiveByUserAndDatasets(user.getUserId(),
                Collections.singletonList(datasetId), now).isEmpty()) {
            throw RegistrationException.conflict("GRANT_ALREADY_ACTIVE",
                    "an unexpired grant for this dataset already exists");
        }
        long ttlMinutes = ttlMinutes();
        DatasetAccessGrant grant = new DatasetAccessGrant();
        grant.setUserId(user.getUserId());
        grant.setDatasetId(datasetId);
        grant.setReason(reason);
        grant.setCreatedAt(now);
        grant.setExpiresAt(now.plusMinutes(ttlMinutes));
        grants.insert(grant);
        audits.insert(grantEvent(subject, dataset, grant, ttlMinutes, requestId, clientIp));
        return new DatasetUsageModels.GrantIssued(grant.getGrantId(), datasetId,
                grant.getExpiresAt().toInstant(ZoneOffset.UTC), ttlMinutes);
    }

    long ttlMinutes() {
        return Math.max(1L, properties.getGrantTtlMinutes());
    }

    private List<RegisteredDataset> deniedDatasets(Subject subject, Collection<Long> datasetIds,
                                                   LocalDateTime now) {
        List<Long> ids = distinctNonNull(datasetIds);
        if (ids.isEmpty() || subject.isAdmin()) return Collections.emptyList();

        Map<Long, RegisteredDataset> registered = new HashMap<>();
        for (RegisteredDataset dataset : grants.findDatasetOwnership(ids)) {
            registered.put(dataset.getDatasetId(), dataset);
        }
        List<Long> needGrant = new ArrayList<>();
        for (Long id : ids) {
            RegisteredDataset dataset = registered.get(id);
            if (dataset != null && basis(subject, dataset.getOwnerDomainId(), false) == null) {
                needGrant.add(id);
            }
        }
        if (needGrant.isEmpty()) return Collections.emptyList();

        Set<Long> granted = new HashSet<>();
        if (subject.userId() != null) {
            for (DatasetAccessGrant grant : grants.findActiveByUserAndDatasets(subject.userId(), needGrant, now)) {
                granted.add(grant.getDatasetId());
            }
        }
        List<RegisteredDataset> denied = new ArrayList<>();
        for (Long id : needGrant) {
            if (!granted.contains(id)) denied.add(registered.get(id));
        }
        return denied;
    }

    private Map<Long, DatasetAccessGrant> activeGrantsByDataset(Long userId, LocalDateTime now) {
        Map<Long, DatasetAccessGrant> result = new HashMap<>();
        if (userId == null) return result;
        // Ordered by expires_at DESC: the first row per dataset is the longest-lived one.
        for (DatasetAccessGrant grant : grants.findActiveByUser(userId, now)) {
            result.putIfAbsent(grant.getDatasetId(), grant);
        }
        return result;
    }

    private DatasetUsageModels.Item item(Subject subject, RegisteredDataset dataset,
                                         DatasetAccessGrant activeGrant) {
        String basis = basis(subject, dataset.getOwnerDomainId(), activeGrant != null);
        DatasetUsageModels.Item item = new DatasetUsageModels.Item();
        item.setDatasetId(dataset.getDatasetId());
        item.setName(dataset.getName());
        item.setDatasetCode(dataset.getDatasetCode());
        item.setVersion(dataset.getDatasetVersion());
        item.setStatus(dataset.getStatus());
        item.setOwnerDomainId(dataset.getOwnerDomainId());
        item.setOwnerDomainName(dataset.getOwnerDomainName());
        item.setAccessible(basis != null);
        item.setBasis(basis);
        if (BASIS_GRANT.equals(basis)) {
            item.setGrantId(activeGrant.getGrantId());
            item.setGrantExpiresAt(activeGrant.getExpiresAt().toInstant(ZoneOffset.UTC));
            item.setGrantReason(activeGrant.getReason());
        }
        return item;
    }

    /** ADMIN, then OWN_DOMAIN, then GRANT; null means not accessible. */
    private static String basis(Subject subject, Long ownerDomainId, boolean activeGrant) {
        if (subject.isAdmin()) return BASIS_ADMIN;
        if (subject.ownsDomain(ownerDomainId)) return BASIS_OWN_DOMAIN;
        return activeGrant ? BASIS_GRANT : null;
    }

    private DatasetAccessAuditEvent denialEvent(Subject subject, RegisteredDataset dataset,
                                                String requestId, String clientIp) {
        DatasetAccessAuditEvent event = baseEvent(subject, dataset, requestId, clientIp);
        event.setAction(ACTION_TASK_CREATE);
        event.setPolicyRule(truncate(subject.denialRule(dataset.getOwnerDomainId()), 256));
        event.setDecision("DENIED");
        event.setReason(REASON_ACCESS_DENIED);
        return event;
    }

    private DatasetAccessAuditEvent grantEvent(Subject subject, RegisteredDataset dataset,
                                               DatasetAccessGrant grant, long ttlMinutes,
                                               String requestId, String clientIp) {
        DatasetAccessAuditEvent event = baseEvent(subject, dataset, requestId, clientIp);
        event.setAction(ACTION_GRANT_ISSUE);
        event.setPolicyRule(truncate("SELF_SERVICE_GRANT;grantId=" + grant.getGrantId()
                + ";ttlMinutes=" + ttlMinutes + subject.actorSuffix(), 256));
        event.setDecision("ALLOWED");
        event.setReason(REASON_GRANT_ISSUED);
        event.setTokenExpiresAt(grant.getExpiresAt());
        return event;
    }

    private DatasetAccessAuditEvent baseEvent(Subject subject, RegisteredDataset dataset,
                                              String requestId, String clientIp) {
        DatasetAccessAuditEvent event = new DatasetAccessAuditEvent();
        event.setRequestId(truncate(trimToNull(requestId), 128));
        event.setPrincipal(truncate(subject.principal(), 128));
        event.setDatasetId(String.valueOf(dataset.getDatasetId()));
        event.setDatasetVersion(truncate(dataset.getDatasetVersion(), 128));
        event.setClientIp(truncate(trimToNull(clientIp), 64));
        return event;
    }

    private Subject currentSubject() {
        return currentUsers.findCurrentUser().map(Subject::of).orElseGet(Subject::unidentified);
    }

    private Instant now() {
        // DATETIME(3) keeps milliseconds; truncating keeps responses equal to stored values.
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String currentClientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes) {
            return ((ServletRequestAttributes) attributes).getRequest().getRemoteAddr();
        }
        return null;
    }

    private static List<Long> distinctNonNull(Collection<Long> ids) {
        if (ids == null) return Collections.emptyList();
        Set<Long> ordered = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) ordered.add(id);
        }
        return new ArrayList<>(ordered);
    }

    private static List<Long> idsOf(List<RegisteredDataset> datasets) {
        return datasets.stream().map(RegisteredDataset::getDatasetId).collect(Collectors.toList());
    }

    private static RegistrationException badRequest(String message) {
        return new RegistrationException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** The effective identity the rule is evaluated for. */
    private static final class Subject {
        private final AuthenticatedUser user;
        private final String principal;

        private Subject(AuthenticatedUser user, String principal) {
            this.user = user;
            this.principal = principal;
        }

        static Subject of(AuthenticatedUser user) {
            return new Subject(user, user.getUsername());
        }

        /** Non-JWT principal: no roles, no domain, no grants. */
        static Subject unidentified() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String name = authentication == null ? null : trimToNull(authentication.getName());
            return new Subject(null, name == null ? "UNKNOWN" : name);
        }

        String principal() { return principal; }

        Long userId() { return user == null ? null : user.getUserId(); }

        boolean isAdmin() { return user != null && user.hasRole("ADMIN"); }

        boolean ownsDomain(Long ownerDomainId) {
            return user != null && user.hasRole("DATA_OWNER") && user.getDomainId() != null
                    && ownerDomainId != null && user.getDomainId().equals(ownerDomainId);
        }

        String denialRule(Long ownerDomainId) {
            if (user == null) return "NO_USER_IDENTITY";
            return "DOMAIN_OR_GRANT;userDomain=" + orNone(user.getDomainId())
                    + ";datasetDomain=" + orNone(ownerDomainId)
                    + ";roles=" + (user.getRoles().isEmpty() ? "none" : String.join("|", user.getRoles()))
                    + actorSuffix();
        }

        String actorSuffix() {
            return user != null && user.isImpersonated() ? ";actor=" + user.getActorUsername() : "";
        }

        private static String orNone(Long value) {
            return value == null ? "none" : String.valueOf(value);
        }
    }
}
