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
 * located in that domain (OWN_DOMAIN); anyone may use a dataset for which they
 * hold an unexpired grant (GRANT). A dataset is located in the domain(s) of the
 * nodes currently holding its replicas ({@link DatasetDomainMapper}), so it
 * follows copy/move scheduling; a dataset in two domains serves both. Datasets
 * located in no enabled domain are reachable by non-admins only through a
 * grant. registered_dataset.owner_domain_id (the 数据归属 holder used by privacy
 * computing) does not influence this decision.
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
    static final int MAX_GRANT_LOG_ROWS = 1000;

    private final CurrentUserService currentUsers;
    private final DatasetAccessGrantMapper grants;
    private final DatasetDomainMapper locations;
    private final DatasetAccessAuditMapper audits;
    private final DatasetRegistrationMapper datasets;
    private final DatasetAccessProperties properties;
    private final TransactionTemplate independentAuditTransaction;
    private final Clock clock;

    @Autowired
    public DatasetUsagePolicyService(CurrentUserService currentUsers,
                                     DatasetAccessGrantMapper grants,
                                     DatasetDomainMapper locations,
                                     DatasetAccessAuditMapper audits,
                                     DatasetRegistrationMapper datasets,
                                     DatasetAccessProperties properties,
                                     PlatformTransactionManager transactionManager) {
        this(currentUsers, grants, locations, audits, datasets, properties, transactionManager, Clock.systemUTC());
    }

    DatasetUsagePolicyService(CurrentUserService currentUsers,
                              DatasetAccessGrantMapper grants,
                              DatasetDomainMapper locations,
                              DatasetAccessAuditMapper audits,
                              DatasetRegistrationMapper datasets,
                              DatasetAccessProperties properties,
                              PlatformTransactionManager transactionManager,
                              Clock clock) {
        this.currentUsers = currentUsers;
        this.grants = grants;
        this.locations = locations;
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
        List<Denial> denied = deniedDatasets(subject, datasetIds, utc(now()));
        if (!denied.isEmpty()) {
            String clientIp = currentClientIp();
            List<DatasetAccessAuditEvent> events = denied.stream()
                    .map(denial -> denialEvent(subject, denial, requestId, clientIp))
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
        Map<Long, Location> located = byDataset(locations.findAllLocationDomains());
        List<DatasetUsageModels.Item> items = new ArrayList<>();
        for (RegisteredDataset dataset : datasets.listDatasets(null, null)) {
            Long id = dataset.getDatasetId();
            items.add(item(subject, dataset, locationOf(located, id), active.get(id)));
        }
        return new DatasetUsageModels.Overview(now, ttlMinutes(), items);
    }

    /**
     * 访问申请日志 (ADMIN only): the newest grants with applicant, dataset, reason and
     * whether each is still active at the returned serverTime.
     */
    public DatasetUsageModels.GrantLog grantLog(int limit) {
        AuthenticatedUser user = currentUsers.require();
        if (!user.hasRole("ADMIN")) {
            throw new RegistrationException(HttpStatus.FORBIDDEN, "ROLE_REQUIRED", "required role: ADMIN");
        }
        Instant now = now();
        LocalDateTime utcNow = utc(now);
        List<DatasetUsageModels.GrantLogItem> items = new ArrayList<>();
        for (DatasetAccessGrantLogRow row : grants.findGrantLog(Math.max(1, Math.min(limit, MAX_GRANT_LOG_ROWS)))) {
            DatasetUsageModels.GrantLogItem item = new DatasetUsageModels.GrantLogItem();
            item.setGrantId(row.getGrantId());
            item.setUserId(row.getUserId());
            item.setUsername(row.getUsername());
            item.setDisplayName(row.getDisplayName());
            item.setDomainName(row.getDomainName());
            item.setDatasetId(row.getDatasetId());
            item.setDatasetName(row.getDatasetName());
            item.setDatasetCode(row.getDatasetCode());
            item.setDatasetVersion(row.getDatasetVersion());
            item.setReason(row.getReason());
            item.setCreatedAt(row.getCreatedAt() == null ? null : row.getCreatedAt().toInstant(ZoneOffset.UTC));
            item.setExpiresAt(row.getExpiresAt() == null ? null : row.getExpiresAt().toInstant(ZoneOffset.UTC));
            // Same rule as the usage check: a grant is active while expires_at > now.
            item.setActive(row.getExpiresAt() != null && row.getExpiresAt().isAfter(utcNow));
            items.add(item);
        }
        return new DatasetUsageModels.GrantLog(now, items);
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
        String standing = basis(subject,
                locationOf(locationsOf(Collections.singletonList(datasetId)), datasetId), false);
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

    private List<Denial> deniedDatasets(Subject subject, Collection<Long> datasetIds, LocalDateTime now) {
        List<Long> ids = distinctNonNull(datasetIds);
        if (ids.isEmpty() || subject.isAdmin()) return Collections.emptyList();

        Map<Long, RegisteredDataset> registered = new HashMap<>();
        for (RegisteredDataset dataset : grants.findLiveDatasets(ids)) {
            registered.put(dataset.getDatasetId(), dataset);
        }
        List<Long> known = ids.stream().filter(registered::containsKey).collect(Collectors.toList());
        if (known.isEmpty()) return Collections.emptyList();

        Map<Long, Location> located = locationsOf(known);
        List<Long> needGrant = new ArrayList<>();
        for (Long id : known) {
            if (basis(subject, locationOf(located, id), false) == null) needGrant.add(id);
        }
        if (needGrant.isEmpty()) return Collections.emptyList();

        Set<Long> granted = new HashSet<>();
        if (subject.userId() != null) {
            for (DatasetAccessGrant grant : grants.findActiveByUserAndDatasets(subject.userId(), needGrant, now)) {
                granted.add(grant.getDatasetId());
            }
        }
        List<Denial> denied = new ArrayList<>();
        for (Long id : needGrant) {
            if (!granted.contains(id)) denied.add(new Denial(registered.get(id), locationOf(located, id)));
        }
        return denied;
    }

    private Map<Long, Location> locationsOf(Collection<Long> datasetIds) {
        if (datasetIds.isEmpty()) return Collections.emptyMap();
        return byDataset(locations.findLocationDomains(datasetIds));
    }

    private static Map<Long, Location> byDataset(List<DatasetDomainLocation> rows) {
        Map<Long, Location> result = new HashMap<>();
        for (DatasetDomainLocation row : rows) {
            if (row.getDatasetId() == null || row.getDomainId() == null) continue;
            result.computeIfAbsent(row.getDatasetId(), id -> new Location())
                    .add(row.getDomainId(), row.getDomainName());
        }
        return result;
    }

    private static Location locationOf(Map<Long, Location> located, Long datasetId) {
        Location location = located.get(datasetId);
        return location == null ? Location.NOWHERE : location;
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

    private DatasetUsageModels.Item item(Subject subject, RegisteredDataset dataset, Location location,
                                         DatasetAccessGrant activeGrant) {
        String basis = basis(subject, location, activeGrant != null);
        DatasetUsageModels.Item item = new DatasetUsageModels.Item();
        item.setDatasetId(dataset.getDatasetId());
        item.setName(dataset.getName());
        item.setDatasetCode(dataset.getDatasetCode());
        item.setVersion(dataset.getDatasetVersion());
        item.setStatus(dataset.getStatus());
        item.setDomainIds(new ArrayList<>(location.domainIds));
        item.setDomainNames(new ArrayList<>(location.domainNames));
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
    private static String basis(Subject subject, Location location, boolean activeGrant) {
        if (subject.isAdmin()) return BASIS_ADMIN;
        if (subject.belongsToAnyOf(location.domainIds)) return BASIS_OWN_DOMAIN;
        return activeGrant ? BASIS_GRANT : null;
    }

    private DatasetAccessAuditEvent denialEvent(Subject subject, Denial denial,
                                                String requestId, String clientIp) {
        DatasetAccessAuditEvent event = baseEvent(subject, denial.dataset, requestId, clientIp);
        event.setAction(ACTION_TASK_CREATE);
        event.setPolicyRule(truncate(subject.denialRule(denial.location.domainIds), 256));
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

    private static List<Long> idsOf(List<Denial> denied) {
        return denied.stream().map(denial -> denial.dataset.getDatasetId()).collect(Collectors.toList());
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

        /** Only a DATA_OWNER bound to a domain is limited to (and entitled to) its domain's datasets. */
        boolean belongsToAnyOf(List<Long> datasetDomainIds) {
            return user != null && user.hasRole("DATA_OWNER") && user.getDomainId() != null
                    && datasetDomainIds.contains(user.getDomainId());
        }

        String denialRule(List<Long> datasetDomainIds) {
            if (user == null) return "NO_USER_IDENTITY";
            return "LOCATION_DOMAIN_OR_GRANT;userDomain=" + orNone(user.getDomainId())
                    + ";datasetDomains=" + (datasetDomainIds.isEmpty() ? "none"
                            : datasetDomainIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
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

    /** The enabled domains a dataset is located in, ordered by domain id (the mapper's order). */
    private static final class Location {
        static final Location NOWHERE = new Location(Collections.<Long>emptyList(), Collections.<String>emptyList());

        final List<Long> domainIds;
        final List<String> domainNames;

        Location() {
            this(new ArrayList<Long>(), new ArrayList<String>());
        }

        private Location(List<Long> domainIds, List<String> domainNames) {
            this.domainIds = domainIds;
            this.domainNames = domainNames;
        }

        void add(Long domainId, String domainName) {
            if (domainIds.contains(domainId)) return;
            domainIds.add(domainId);
            domainNames.add(domainName);
        }
    }

    /** A dataset the subject may not use, with the location recorded in the denial audit row. */
    private static final class Denial {
        final RegisteredDataset dataset;
        final Location location;

        Denial(RegisteredDataset dataset, Location location) {
            this.dataset = dataset;
            this.location = location;
        }
    }
}
