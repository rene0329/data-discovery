package org.example.security.access;

import org.example.auth.AuthException;
import org.example.auth.AuthenticatedUser;
import org.example.auth.CurrentUserService;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.handler.RegistrationExceptionHandler;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.vo.ApiV1Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetUsagePolicyServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00.123Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private static final AuthenticatedUser ADMIN = user(1L, "admin", null, "ADMIN");
    private static final AuthenticatedUser OWNER_A = user(7L, "owner-a", 1L, "DATA_OWNER");
    private static final AuthenticatedUser OWNER_NO_DOMAIN = user(8L, "owner-free", null, "DATA_OWNER");
    private static final AuthenticatedUser AUDITOR_A = user(9L, "auditor-a", 1L, "AUDITOR");
    private static final AuthenticatedUser OWNER_B = user(10L, "owner-b", 2L, "DATA_OWNER");
    private static final AuthenticatedUser OWNER_C = user(11L, "owner-c", 3L, "DATA_OWNER");
    private static final AuthenticatedUser OWNER_D = user(12L, "owner-d", 4L, "DATA_OWNER");

    // Nodes: 11/12 in site sh (domain 1), 21 in sz (domain 2), 31 in bj (domain 3),
    // 41 in hz (domain 4, disabled), 51 in an unmapped site, 61 without a site.
    private static final int SH_1 = 11;
    private static final int SH_2 = 12;
    private static final int SZ_1 = 21;
    private static final int BJ_1 = 31;
    private static final int HZ_1 = 41;
    private static final int GZ_1 = 51;
    private static final int NO_SITE = 61;

    private InMemoryGrants grants;
    private InMemoryLocations locations;
    private DatasetAccessAuditMapper audits;
    private PlatformTransactionManager transactions;
    private DatasetAccessProperties properties;
    private DatasetUsagePolicyService service;

    @BeforeEach
    void setUp() {
        grants = new InMemoryGrants();
        locations = new InMemoryLocations(grants);
        locations.domain(1L, "Domain A", "sh", true);
        locations.domain(2L, "Domain B", "sz", true);
        locations.domain(3L, "Domain C", "bj", true);
        locations.domain(4L, "Domain D", "hz", false);
        locations.domain(5L, "Domain without site", null, true);
        locations.node(SH_1, "sh");
        locations.node(SH_2, "sh");
        locations.node(SZ_1, "sz");
        locations.node(BJ_1, "bj");
        locations.node(HZ_1, "hz");
        locations.node(GZ_1, "gz");
        locations.node(NO_SITE, null);
        // 1 is located in A, 2 in B, 3 nowhere (no replica at all).
        grants.dataset(1L, "v1");
        grants.dataset(2L, "v2");
        grants.dataset(3L, "v3");
        locations.replica(1L, SH_1, "AVAILABLE");
        locations.replica(2L, SZ_1, "AVAILABLE");
        DatasetRegistrationMapper datasets = mock(DatasetRegistrationMapper.class);
        when(datasets.listDatasets(null, null)).thenAnswer(inv -> grants.datasets.values().stream()
                .filter(dataset -> !grants.deleted.contains(dataset.getDatasetId())).collect(Collectors.toList()));
        when(datasets.findDatasetById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return grants.deleted.contains(id) ? null : grants.datasets.get(id);
        });
        audits = mock(DatasetAccessAuditMapper.class);
        transactions = mock(PlatformTransactionManager.class);
        properties = new DatasetAccessProperties();
        service = new DatasetUsagePolicyService(new CurrentUserService(), grants, locations, audits, datasets,
                properties, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ---- rule matrix -------------------------------------------------------

    @Test
    void adminMayUseEveryDatasetIncludingOnesLocatedInNoDomain() {
        login(ADMIN);

        assertEquals(Collections.emptyList(), service.inaccessibleDatasetIds(Arrays.asList(1L, 2L, 3L)));
        for (DatasetUsageModels.Item item : service.overview().getItems()) {
            assertTrue(item.isAccessible());
            assertEquals("ADMIN", item.getBasis());
        }
    }

    @Test
    void dataOwnerMayUseOwnDomainOnlyAndOrderIsPreserved() {
        login(OWNER_A);

        assertEquals(Arrays.asList(3L, 2L), service.inaccessibleDatasetIds(Arrays.asList(3L, 1L, 2L)));
        Map<Long, DatasetUsageModels.Item> items = itemsById();
        assertEquals("OWN_DOMAIN", items.get(1L).getBasis());
        assertTrue(items.get(1L).isAccessible());
        assertFalse(items.get(2L).isAccessible());
        assertNull(items.get(2L).getBasis());
        assertFalse(items.get(3L).isAccessible(), "a dataset located in no domain needs a grant");
    }

    @Test
    void datasetInTwoDomainsServesBothDomainsAndNobodyElse() {
        grants.dataset(4L, "v4");
        locations.replica(4L, SH_2, "AVAILABLE");
        locations.replica(4L, SZ_1, "AVAILABLE");
        locations.replica(4L, SH_1, "AVAILABLE");

        login(OWNER_A);
        assertEquals(Collections.emptyList(), service.inaccessibleDatasetIds(Collections.singletonList(4L)));
        DatasetUsageModels.Item item = itemsById().get(4L);
        assertEquals("OWN_DOMAIN", item.getBasis());
        assertEquals(Arrays.asList(1L, 2L), item.getDomainIds(), "one entry per domain, ordered by domain id");
        assertEquals(Arrays.asList("Domain A", "Domain B"), item.getDomainNames());

        login(OWNER_B);
        assertEquals(Collections.emptyList(), service.inaccessibleDatasetIds(Collections.singletonList(4L)));
        assertEquals("OWN_DOMAIN", itemsById().get(4L).getBasis());

        login(OWNER_C);
        assertEquals(Collections.singletonList(4L),
                service.checkAndAuditTaskDatasets(Collections.singletonList(4L), "req-two"));
        assertEquals("LOCATION_DOMAIN_OR_GRANT;userDomain=3;datasetDomains=1,2;roles=DATA_OWNER",
                singleAuditEvent().getPolicyRule());
    }

    @Test
    void missingAndVerifyFailedReplicasDoNotLocateADataset() {
        grants.dataset(4L, "v4");
        locations.replica(4L, SH_1, "MISSING");
        locations.replica(4L, SH_2, "VERIFY_FAILED");

        login(OWNER_A);
        assertEquals(Collections.singletonList(4L), service.inaccessibleDatasetIds(Collections.singletonList(4L)));
        DatasetUsageModels.Item item = itemsById().get(4L);
        assertFalse(item.isAccessible());
        assertEquals(Collections.emptyList(), item.getDomainIds());
        assertEquals(Collections.emptyList(), item.getDomainNames());
    }

    @Test
    void everyOtherReplicaStateStillLocatesTheDataset() {
        String[] states = {"AVAILABLE", "VERIFYING", "UNVERIFIED", "UNAVAILABLE"};
        for (int i = 0; i < states.length; i++) {
            long datasetId = 10L + i;
            grants.dataset(datasetId, "v");
            locations.replica(datasetId, SH_1, states[i]);
        }

        login(OWNER_A);
        assertEquals(Collections.emptyList(),
                service.inaccessibleDatasetIds(Arrays.asList(10L, 11L, 12L, 13L)));
        Map<Long, DatasetUsageModels.Item> items = itemsById();
        for (long id = 10L; id <= 13L; id++) {
            assertEquals("OWN_DOMAIN", items.get(id).getBasis(), "replica state of dataset " + id);
            assertEquals(Collections.singletonList(1L), items.get(id).getDomainIds());
        }
    }

    @Test
    void movedDatasetFollowsItsReplicasToTheTargetDomain() {
        // After a MOVE from sh to sz the source row stays behind as MISSING.
        grants.dataset(4L, "v4");
        locations.replica(4L, SH_1, "MISSING");
        locations.replica(4L, SZ_1, "AVAILABLE");

        login(OWNER_B);
        assertEquals(Collections.emptyList(), service.inaccessibleDatasetIds(Collections.singletonList(4L)));
        assertEquals("OWN_DOMAIN", itemsById().get(4L).getBasis());

        login(OWNER_A);
        assertEquals(Collections.singletonList(4L),
                service.checkAndAuditTaskDatasets(Collections.singletonList(4L), "req-moved"));
        assertEquals("LOCATION_DOMAIN_OR_GRANT;userDomain=1;datasetDomains=2;roles=DATA_OWNER",
                singleAuditEvent().getPolicyRule());
        DatasetUsageModels.Item item = itemsById().get(4L);
        assertEquals(Collections.singletonList(2L), item.getDomainIds());
        assertEquals(Collections.singletonList("Domain B"), item.getDomainNames());
    }

    @Test
    void disabledDomainDoesNotCount() {
        grants.dataset(4L, "v4");
        locations.replica(4L, HZ_1, "AVAILABLE");
        locations.replica(4L, BJ_1, "AVAILABLE");

        login(OWNER_D);
        assertEquals(Collections.singletonList(4L), service.inaccessibleDatasetIds(Collections.singletonList(4L)));

        login(OWNER_C);
        assertEquals(Collections.emptyList(), service.inaccessibleDatasetIds(Collections.singletonList(4L)));
        assertEquals(Collections.singletonList(3L), itemsById().get(4L).getDomainIds());
    }

    @Test
    void replicasOutsideEveryDomainSiteOrOnUnregisteredNodesDoNotLocateADataset() {
        grants.dataset(4L, "v4");
        locations.replica(4L, GZ_1, "AVAILABLE");
        locations.replica(4L, NO_SITE, "AVAILABLE");
        locations.replica(4L, 99, "AVAILABLE");
        grants.dataset(5L, "v5");
        locations.node(71, "sh");
        locations.deletedNodes.add(71);
        locations.replica(5L, 71, "AVAILABLE");

        login(OWNER_A);
        assertEquals(Arrays.asList(4L, 5L), service.inaccessibleDatasetIds(Arrays.asList(4L, 5L)));
        assertEquals(Collections.emptyList(), itemsById().get(4L).getDomainIds());
        assertEquals(Collections.emptyList(), itemsById().get(5L).getDomainIds());
    }

    @Test
    void onlyTheReplicaLocationDecidesTheDomain() {
        // Registered by a domain-A user but now stored in B, and the reverse: there is
        // no per-dataset holder any more, only where the replicas are.
        grants.dataset(4L, "v4");
        locations.replica(4L, SZ_1, "AVAILABLE");
        grants.dataset(5L, "v5");
        locations.replica(5L, SH_1, "AVAILABLE");

        login(OWNER_A);
        assertEquals(Collections.singletonList(4L), service.inaccessibleDatasetIds(Arrays.asList(4L, 5L)));
        Map<Long, DatasetUsageModels.Item> items = itemsById();
        assertNull(items.get(4L).getBasis());
        assertEquals(Collections.singletonList(2L), items.get(4L).getDomainIds());
        assertEquals("OWN_DOMAIN", items.get(5L).getBasis());
        assertEquals(Collections.singletonList(1L), items.get(5L).getDomainIds());
        // Not "already accessible": only the location counts.
        assertNotNull(service.issueGrant(new DatasetUsageModels.GrantRequest(4L, "stored in B"),
                "req-own", null).getGrantId());
        assertEquals(Collections.emptyList(), service.inaccessibleDatasetIds(Collections.singletonList(4L)));
    }

    @Test
    void softDeletedDatasetsAreIgnored() {
        grants.dataset(4L, "v4");
        locations.replica(4L, SZ_1, "AVAILABLE");
        grants.deleted.add(4L);

        login(OWNER_A);
        assertEquals(Collections.emptyList(), service.inaccessibleDatasetIds(Collections.singletonList(4L)));
        assertFalse(itemsById().containsKey(4L));
        assertTrue(locations.findAllLocationDomains().stream().noneMatch(row -> row.getDatasetId() == 4L));
    }

    @Test
    void domainlessOwnerAndAuditorOfTheSameDomainNeedGrantsForEverything() {
        login(OWNER_NO_DOMAIN);
        assertEquals(Arrays.asList(1L, 2L, 3L), service.inaccessibleDatasetIds(Arrays.asList(1L, 2L, 3L)));

        login(AUDITOR_A);
        assertEquals(Arrays.asList(1L, 2L, 3L), service.inaccessibleDatasetIds(Arrays.asList(1L, 2L, 3L)));
    }

    @Test
    void activeGrantOpensDatasetButExpiredOrJustExpiringGrantDoesNot() {
        login(OWNER_A);
        DatasetAccessGrant active = grants.grant(OWNER_A.getUserId(), 2L, "need B", NOW_UTC.plusMinutes(1));
        grants.grant(OWNER_A.getUserId(), 3L, "old", NOW_UTC.minusNanos(1_000_000));
        grants.grant(OWNER_A.getUserId(), 3L, "boundary", NOW_UTC);
        grants.grant(AUDITOR_A.getUserId(), 3L, "someone else's", NOW_UTC.plusMinutes(5));

        assertEquals(Collections.singletonList(3L), service.inaccessibleDatasetIds(Arrays.asList(1L, 2L, 3L)));

        Map<Long, DatasetUsageModels.Item> items = itemsById();
        assertEquals("GRANT", items.get(2L).getBasis());
        assertTrue(items.get(2L).isAccessible());
        assertEquals(active.getGrantId(), items.get(2L).getGrantId());
        assertEquals(NOW.plusSeconds(60), items.get(2L).getGrantExpiresAt());
        assertEquals("need B", items.get(2L).getGrantReason());
        assertFalse(items.get(3L).isAccessible());
        assertNull(items.get(3L).getGrantId());
        assertNull(items.get(3L).getGrantExpiresAt());
        assertNull(items.get(1L).getGrantId(), "grant fields are only filled for basis GRANT");
    }

    @Test
    void impersonationEvaluatesTheEffectiveUserAndRecordsTheActor() {
        AuthenticatedUser ownerBAsAdmin = new AuthenticatedUser(10L, "owner-b", "Owner B",
                Collections.singleton("DATA_OWNER"), 2L, "DOMAIN_B", "Domain B", true, 1L, "admin");
        login(ownerBAsAdmin);

        assertEquals(Arrays.asList(1L, 3L), service.inaccessibleDatasetIds(Arrays.asList(1L, 2L, 3L)));

        service.checkAndAuditTaskDatasets(Collections.singletonList(1L), "req-imp");
        DatasetAccessAuditEvent event = singleAuditEvent();
        assertEquals("owner-b", event.getPrincipal());
        assertTrue(event.getPolicyRule().endsWith(";actor=admin"), event.getPolicyRule());
    }

    @Test
    void unknownOrDeletedDatasetIdsAreLeftToTheCallersExistenceCheck() {
        login(OWNER_A);

        assertEquals(Collections.singletonList(2L),
                service.inaccessibleDatasetIds(Arrays.asList(99L, 2L, null, 2L)));
    }

    // ---- auditing ----------------------------------------------------------

    @Test
    void pureCheckNeverWritesAuditRows() {
        login(OWNER_A);

        assertEquals(Collections.singletonList(2L), service.inaccessibleDatasetIds(Collections.singletonList(2L)));

        verify(audits, never()).insert(any());
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    void checkAndAuditWritesOneDeniedRowPerInaccessibleDatasetInItsOwnTransaction() {
        login(OWNER_A);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        List<Long> denied = service.checkAndAuditTaskDatasets(Arrays.asList(1L, 2L, 3L, 2L), "req-1");

        assertEquals(Arrays.asList(2L, 3L), denied);
        ArgumentCaptor<DatasetAccessAuditEvent> captor = ArgumentCaptor.forClass(DatasetAccessAuditEvent.class);
        verify(audits, times(2)).insert(captor.capture());
        DatasetAccessAuditEvent first = captor.getAllValues().get(0);
        assertEquals("req-1", first.getRequestId());
        assertEquals("owner-a", first.getPrincipal());
        assertEquals("2", first.getDatasetId());
        assertEquals("v2", first.getDatasetVersion());
        assertEquals("TASK_CREATE", first.getAction());
        assertEquals("DENIED", first.getDecision());
        assertEquals("CROSS_DOMAIN_ACCESS_DENIED", first.getReason());
        assertEquals("LOCATION_DOMAIN_OR_GRANT;userDomain=1;datasetDomains=2;roles=DATA_OWNER",
                first.getPolicyRule());
        assertEquals("10.0.0.8", first.getClientIp());
        assertNull(first.getFilePath());
        assertNull(first.getTargetNode());
        assertNull(first.getRunId());
        assertNull(first.getJti());
        assertNull(first.getTokenExpiresAt());
        assertEquals("3", captor.getAllValues().get(1).getDatasetId());
        assertEquals("LOCATION_DOMAIN_OR_GRANT;userDomain=1;datasetDomains=none;roles=DATA_OWNER",
                captor.getAllValues().get(1).getPolicyRule());

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.getValue().getPropagationBehavior());
        verify(transactions).commit(any());
    }

    @Test
    void allAccessibleSelectionWritesNothing() {
        login(OWNER_A);
        grants.grant(OWNER_A.getUserId(), 2L, "ok", NOW_UTC.plusMinutes(10));

        service.requireTaskDatasetsAccessible(Arrays.asList(1L, 2L), "req-ok");

        verify(audits, never()).insert(any());
    }

    @Test
    void oneOutOfScopeDatasetAmongSeveralFailsTaskCreationWith403() {
        login(OWNER_A);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.requireTaskDatasetsAccessible(Arrays.asList(1L, 2L), "req-2"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals("DATASET_ACCESS_DENIED", error.getErrorCode());
        assertEquals("任务创建失败，用户访问受限", error.getMessage());
        assertEquals("2", singleAuditEvent().getDatasetId());

        ResponseEntity<ApiV1Response<Object>> rendered =
                new RegistrationExceptionHandler().handleRegistrationException(error);
        assertEquals(403, rendered.getStatusCodeValue());
        assertEquals(403, rendered.getBody().getCode());
        assertEquals("DATASET_ACCESS_DENIED", rendered.getBody().getErrorCode());
        assertEquals("任务创建失败，用户访问受限", rendered.getBody().getMsg());
    }

    @Test
    void nonJwtPrincipalFailsClosedWithForbiddenInsteadOfAuthError() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "internal-agent", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_INTERNAL_AGENT"))));

        assertEquals(Arrays.asList(1L, 2L, 3L), service.inaccessibleDatasetIds(Arrays.asList(1L, 2L, 3L)));
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.requireTaskDatasetsAccessible(Collections.singletonList(1L), "req-3"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        DatasetAccessAuditEvent event = singleAuditEvent();
        assertEquals("internal-agent", event.getPrincipal());
        assertEquals("NO_USER_IDENTITY", event.getPolicyRule());
    }

    @Test
    void missingSecurityContextIsAuditedAsUnknownPrincipal() {
        assertThrows(RegistrationException.class,
                () -> service.requireTaskDatasetsAccessible(Collections.singletonList(3L), "req-4"));

        assertEquals("UNKNOWN", singleAuditEvent().getPrincipal());
    }

    // ---- overview ------------------------------------------------------------

    @Test
    void overviewCarriesServerTimeTtlAndRegistryFields() {
        login(OWNER_A);
        properties.setGrantTtlMinutes(15);

        DatasetUsageModels.Overview overview = service.overview();

        assertEquals(NOW, overview.getServerTime());
        assertEquals(15L, overview.getTtlMinutes());
        assertEquals(3, overview.getItems().size());
        DatasetUsageModels.Item item = itemsById().get(2L);
        assertEquals("dataset-2", item.getName());
        assertEquals("ds-2", item.getDatasetCode());
        assertEquals("v2", item.getVersion());
        assertEquals("ACTIVE", item.getStatus());
        assertEquals(Collections.singletonList(2L), item.getDomainIds());
        assertEquals(Collections.singletonList("Domain B"), item.getDomainNames());
        DatasetUsageModels.Item nowhere = itemsById().get(3L);
        assertNotNull(nowhere.getDomainIds(), "never null");
        assertTrue(nowhere.getDomainIds().isEmpty());
        assertTrue(nowhere.getDomainNames().isEmpty());
    }

    @Test
    void adminOverviewStillShowsWhereEachDatasetIs() {
        login(ADMIN);

        Map<Long, DatasetUsageModels.Item> items = itemsById();
        assertEquals(Collections.singletonList(1L), items.get(1L).getDomainIds());
        assertEquals(Collections.singletonList("Domain A"), items.get(1L).getDomainNames());
        assertEquals("ADMIN", items.get(3L).getBasis());
        assertTrue(items.get(3L).getDomainIds().isEmpty());
    }

    @Test
    void overviewRequiresASignedInUser() {
        AuthException error = assertThrows(AuthException.class, service::overview);
        assertEquals("AUTH_REQUIRED", error.getErrorCode());
    }

    // ---- grant issuance ----------------------------------------------------

    @Test
    void grantIsUsableImmediatelyAndExpiresAfterConfiguredTtl() {
        login(OWNER_A);
        properties.setGrantTtlMinutes(30);

        DatasetUsageModels.GrantIssued issued = service.issueGrant(
                new DatasetUsageModels.GrantRequest(2L, "  联合训练需要 B 域数据  "), "req-g", "10.0.0.9");

        assertNotNull(issued.getGrantId());
        assertEquals(2L, issued.getDatasetId());
        assertEquals(NOW.plusSeconds(30 * 60), issued.getExpiresAt());
        assertEquals(30L, issued.getTtlMinutes());
        DatasetAccessGrant stored = grants.rows.get(0);
        assertEquals(OWNER_A.getUserId(), stored.getUserId());
        assertEquals("联合训练需要 B 域数据", stored.getReason());
        assertEquals(NOW_UTC, stored.getCreatedAt());
        assertEquals(Collections.singletonList(OWNER_A.getUserId()), grants.lockedUsers);
        assertEquals(Collections.emptyList(), service.inaccessibleDatasetIds(Arrays.asList(1L, 2L)));

        DatasetAccessAuditEvent event = singleAuditEvent();
        assertEquals("ALLOWED", event.getDecision());
        assertEquals("GRANT_ISSUE", event.getAction());
        assertEquals("GRANT_ISSUED", event.getReason());
        assertEquals("owner-a", event.getPrincipal());
        assertEquals("2", event.getDatasetId());
        assertEquals("v2", event.getDatasetVersion());
        assertEquals("req-g", event.getRequestId());
        assertEquals("10.0.0.9", event.getClientIp());
        assertEquals(NOW_UTC.plusMinutes(30), event.getTokenExpiresAt());
        assertEquals("SELF_SERVICE_GRANT;grantId=" + issued.getGrantId() + ";ttlMinutes=30",
                event.getPolicyRule());
    }

    @Test
    void auditorMayApplyForDatasetOfOwnDomain() {
        login(AUDITOR_A);

        service.issueGrant(new DatasetUsageModels.GrantRequest(1L, "audit sampling"), "req-a", null);

        assertEquals(Collections.emptyList(), service.inaccessibleDatasetIds(Collections.singletonList(1L)));
    }

    @Test
    void grantRequestValidation() {
        login(OWNER_A);

        assertBadRequest(null);
        assertBadRequest(new DatasetUsageModels.GrantRequest(null, "reason"));
        assertBadRequest(new DatasetUsageModels.GrantRequest(2L, null));
        assertBadRequest(new DatasetUsageModels.GrantRequest(2L, "   "));
        assertBadRequest(new DatasetUsageModels.GrantRequest(2L, repeat("a", 501)));
        // 500 characters is the limit; characters, not UTF-16 units, are counted like VARCHAR(500).
        service.issueGrant(new DatasetUsageModels.GrantRequest(2L, repeat("数", 500)), "r1", null);
        service.issueGrant(new DatasetUsageModels.GrantRequest(3L, repeat("😀", 300)), "r2", null);
        assertEquals(2, grants.rows.size());
    }

    @Test
    void unknownDatasetIsNotFound() {
        login(OWNER_A);

        RegistrationException error = assertThrows(RegistrationException.class, () -> service.issueGrant(
                new DatasetUsageModels.GrantRequest(99L, "reason"), "req", null));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals("DATASET_NOT_FOUND", error.getErrorCode());
    }

    @Test
    void alreadyAccessibleThroughRoleOrDomainIsConflict() {
        login(ADMIN);
        assertConflict(2L, "DATASET_ALREADY_ACCESSIBLE");

        login(OWNER_A);
        assertConflict(1L, "DATASET_ALREADY_ACCESSIBLE");
        assertTrue(grants.rows.isEmpty());
        verify(audits, never()).insert(any());
    }

    @Test
    void alreadyAccessibleFollowsTheCurrentLocation() {
        grants.dataset(4L, "v4");
        locations.replica(4L, SH_1, "MISSING");
        locations.replica(4L, SZ_1, "AVAILABLE");

        login(OWNER_B);
        assertConflict(4L, "DATASET_ALREADY_ACCESSIBLE");

        login(OWNER_A);
        assertNotNull(service.issueGrant(new DatasetUsageModels.GrantRequest(4L, "moved to B"), "req", null)
                .getGrantId());
    }

    @Test
    void unexpiredGrantIsConflictButExpiredOneCanBeRenewed() {
        login(OWNER_A);
        grants.grant(OWNER_A.getUserId(), 2L, "first", NOW_UTC.plusSeconds(1));
        assertConflict(2L, "GRANT_ALREADY_ACTIVE");

        grants.grant(OWNER_A.getUserId(), 3L, "expired", NOW_UTC);
        DatasetUsageModels.GrantIssued renewed = service.issueGrant(
                new DatasetUsageModels.GrantRequest(3L, "again"), "req", null);
        assertEquals(NOW.plusSeconds(3600), renewed.getExpiresAt());
    }

    @Test
    void nonPositiveTtlIsTreatedAsOneMinute() {
        login(OWNER_A);
        properties.setGrantTtlMinutes(0);

        DatasetUsageModels.GrantIssued issued = service.issueGrant(
                new DatasetUsageModels.GrantRequest(2L, "short"), "req", null);

        assertEquals(1L, issued.getTtlMinutes());
        assertEquals(NOW.plusSeconds(60), issued.getExpiresAt());
    }

    // ---- 访问申请日志 -----------------------------------------------------------

    @Test
    void grantLogListsEveryGrantNewestFirstWithActiveStateAtServerTime() {
        grants.grant(7L, 2L, "need B", NOW_UTC.plusMinutes(30));
        grants.grant(9L, 3L, "audit C", NOW_UTC);
        login(ADMIN);

        DatasetUsageModels.GrantLog log = service.grantLog(500);

        assertEquals(NOW, log.getServerTime());
        assertEquals(Arrays.asList(101L, 100L), log.getItems().stream()
                .map(DatasetUsageModels.GrantLogItem::getGrantId).collect(Collectors.toList()));
        DatasetUsageModels.GrantLogItem lapsed = log.getItems().get(0);
        assertFalse(lapsed.isActive(), "a grant expiring exactly at serverTime is no longer usable");
        assertEquals("user-9", lapsed.getUsername());
        assertEquals("ds-3", lapsed.getDatasetCode());
        assertEquals(NOW, lapsed.getExpiresAt());
        DatasetUsageModels.GrantLogItem active = log.getItems().get(1);
        assertTrue(active.isActive());
        assertEquals(7L, active.getUserId());
        assertEquals("dataset-2", active.getDatasetName());
        assertEquals("v2", active.getDatasetVersion());
        assertEquals("need B", active.getReason());
        assertEquals(NOW.minusSeconds(60), active.getCreatedAt());
        assertEquals(NOW.plusSeconds(1800), active.getExpiresAt());
    }

    @Test
    void grantLogIsForAdministratorsOnlyAndCapsTheRowCount() {
        login(OWNER_A);
        RegistrationException error = assertThrows(RegistrationException.class, () -> service.grantLog(500));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals("ROLE_REQUIRED", error.getErrorCode());
        assertNull(grants.lastLogLimit);

        login(ADMIN);
        service.grantLog(100000);
        assertEquals(1000, grants.lastLogLimit);
        service.grantLog(0);
        assertEquals(1, grants.lastLogLimit);
    }

    // ---- helpers -------------------------------------------------------------

    private void assertBadRequest(DatasetUsageModels.GrantRequest request) {
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.issueGrant(request, "req", null));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("INVALID_ARGUMENT", error.getErrorCode());
    }

    private void assertConflict(Long datasetId, String code) {
        RegistrationException error = assertThrows(RegistrationException.class, () -> service.issueGrant(
                new DatasetUsageModels.GrantRequest(datasetId, "reason"), "req", null));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(code, error.getErrorCode());
    }

    private DatasetAccessAuditEvent singleAuditEvent() {
        ArgumentCaptor<DatasetAccessAuditEvent> captor = ArgumentCaptor.forClass(DatasetAccessAuditEvent.class);
        verify(audits).insert(captor.capture());
        return captor.getValue();
    }

    private Map<Long, DatasetUsageModels.Item> itemsById() {
        Map<Long, DatasetUsageModels.Item> result = new LinkedHashMap<>();
        for (DatasetUsageModels.Item item : service.overview().getItems()) result.put(item.getDatasetId(), item);
        return result;
    }

    private static void login(AuthenticatedUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));
    }

    private static AuthenticatedUser user(Long id, String username, Long domainId, String role) {
        return new AuthenticatedUser(id, username, username, new LinkedHashSet<>(Collections.singletonList(role)),
                domainId, domainId == null ? null : "DOMAIN_" + domainId,
                domainId == null ? null : "Domain " + domainId);
    }

    private static String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) builder.append(value);
        return builder.toString();
    }

    /** Mirrors the SQL of DatasetAccessGrantMapper: active means expires_at > now. */
    static final class InMemoryGrants implements DatasetAccessGrantMapper {
        final Map<Long, RegisteredDataset> datasets = new LinkedHashMap<>();
        final Set<Long> deleted = new HashSet<>();
        final List<DatasetAccessGrant> rows = new ArrayList<>();
        final List<Long> lockedUsers = new ArrayList<>();
        Integer lastLogLimit;
        private long nextId = 100L;

        void dataset(Long id, String version) {
            datasets.put(id, RegisteredDataset.builder().datasetId(id).name("dataset-" + id)
                    .datasetCode("ds-" + id).datasetVersion(version).status("ACTIVE").build());
        }

        DatasetAccessGrant grant(Long userId, Long datasetId, String reason, LocalDateTime expiresAt) {
            DatasetAccessGrant grant = new DatasetAccessGrant();
            grant.setUserId(userId);
            grant.setDatasetId(datasetId);
            grant.setReason(reason);
            grant.setCreatedAt(NOW_UTC.minusMinutes(1));
            grant.setExpiresAt(expiresAt);
            insert(grant);
            return grant;
        }

        @Override
        public int insert(DatasetAccessGrant grant) {
            grant.setGrantId(nextId++);
            rows.add(grant);
            return 1;
        }

        @Override
        public List<DatasetAccessGrant> findActiveByUser(Long userId, LocalDateTime now) {
            return rows.stream()
                    .filter(row -> Objects.equals(row.getUserId(), userId) && row.getExpiresAt().isAfter(now))
                    .sorted(Comparator.comparing(DatasetAccessGrant::getExpiresAt)
                            .thenComparing(DatasetAccessGrant::getGrantId).reversed())
                    .collect(Collectors.toList());
        }

        @Override
        public List<DatasetAccessGrant> findActiveByUserAndDatasets(Long userId, Collection<Long> datasetIds,
                                                                    LocalDateTime now) {
            return findActiveByUser(userId, now).stream()
                    .filter(row -> datasetIds.contains(row.getDatasetId()))
                    .collect(Collectors.toList());
        }

        @Override
        public List<DatasetAccessGrantLogRow> findGrantLog(int limit) {
            lastLogLimit = limit;
            List<DatasetAccessGrantLogRow> result = new ArrayList<>();
            for (int i = rows.size() - 1; i >= 0 && result.size() < limit; i--) {
                DatasetAccessGrant grant = rows.get(i);
                RegisteredDataset dataset = datasets.get(grant.getDatasetId());
                DatasetAccessGrantLogRow row = new DatasetAccessGrantLogRow();
                row.setGrantId(grant.getGrantId());
                row.setUserId(grant.getUserId());
                row.setUsername("user-" + grant.getUserId());
                row.setDatasetId(grant.getDatasetId());
                row.setDatasetName(dataset == null ? null : dataset.getName());
                row.setDatasetCode(dataset == null ? null : dataset.getDatasetCode());
                row.setDatasetVersion(dataset == null ? null : dataset.getDatasetVersion());
                row.setReason(grant.getReason());
                row.setCreatedAt(grant.getCreatedAt());
                row.setExpiresAt(grant.getExpiresAt());
                result.add(row);
            }
            return result;
        }

        @Override
        public Long lockUser(Long userId) {
            lockedUsers.add(userId);
            return userId;
        }

        @Override
        public List<RegisteredDataset> findLiveDatasets(Collection<Long> datasetIds) {
            return datasetIds.stream().filter(id -> !deleted.contains(id))
                    .map(datasets::get).filter(Objects::nonNull).collect(Collectors.toList());
        }
    }

    /**
     * Mirrors the SQL of DatasetDomainMapper: replica -> live dataset -> registered
     * node -> enabled domain of the node's site; MISSING and VERIFY_FAILED replicas
     * do not count; distinct rows ordered by dataset id, domain id.
     */
    static final class InMemoryLocations implements DatasetDomainMapper {
        private final InMemoryGrants registry;
        final Map<Long, String[]> domains = new LinkedHashMap<>();
        final Map<Integer, String> nodeSites = new LinkedHashMap<>();
        final Set<Integer> deletedNodes = new HashSet<>();
        final List<Object[]> replicas = new ArrayList<>();

        InMemoryLocations(InMemoryGrants registry) {
            this.registry = registry;
        }

        void domain(Long id, String name, String siteCode, boolean enabled) {
            domains.put(id, new String[]{name, siteCode, String.valueOf(enabled)});
        }

        void node(int nodeId, String siteCode) {
            nodeSites.put(nodeId, siteCode);
        }

        void replica(Long datasetId, int nodeId, String availability) {
            replicas.add(new Object[]{datasetId, nodeId, availability});
        }

        @Override
        public List<DatasetDomainLocation> findLocationDomains(Collection<Long> datasetIds) {
            return findAllLocationDomains().stream().filter(row -> datasetIds.contains(row.getDatasetId()))
                    .collect(Collectors.toList());
        }

        @Override
        public List<DatasetDomainLocation> findAllLocationDomains() {
            Set<String> seen = new HashSet<>();
            List<DatasetDomainLocation> rows = new ArrayList<>();
            for (Object[] replica : replicas) {
                Long datasetId = (Long) replica[0];
                Integer nodeId = (Integer) replica[1];
                String availability = (String) replica[2];
                if ("MISSING".equals(availability) || "VERIFY_FAILED".equals(availability)) continue;
                if (!registry.datasets.containsKey(datasetId) || registry.deleted.contains(datasetId)) continue;
                if (!nodeSites.containsKey(nodeId) || deletedNodes.contains(nodeId)) continue;
                String site = nodeSites.get(nodeId);
                for (Map.Entry<Long, String[]> domain : domains.entrySet()) {
                    String[] facts = domain.getValue();
                    if (site == null || !site.equals(facts[1]) || !Boolean.parseBoolean(facts[2])) continue;
                    if (seen.add(datasetId + "/" + domain.getKey())) {
                        rows.add(new DatasetDomainLocation(datasetId, domain.getKey(), facts[0]));
                    }
                }
            }
            rows.sort(Comparator.comparing(DatasetDomainLocation::getDatasetId)
                    .thenComparing(DatasetDomainLocation::getDomainId));
            return rows;
        }
    }
}
