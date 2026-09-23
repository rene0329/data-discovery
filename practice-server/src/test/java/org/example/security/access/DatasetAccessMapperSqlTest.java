package org.example.security.access;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetAccessMapperSqlTest {
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        configuration.addMapper(DatasetAccessAuditMapper.class);
        configuration.addMapper(DatasetAccessGrantMapper.class);
        configuration.addMapper(DatasetDomainMapper.class);
    }

    @Test
    void auditEventsFilterDecisionInSqlOnlyWhenRequested() {
        Map<String, Object> params = new HashMap<>();
        params.put("requestId", null);
        params.put("runId", "run-1");
        params.put("principal", null);
        params.put("decision", "DENIED");
        params.put("limit", 50);

        String filtered = sql(DatasetAccessAuditMapper.class, "find", params);
        assertTrue(filtered.contains("AND run_id = ?"), filtered);
        assertTrue(filtered.contains("AND decision = ? ORDER BY event_id DESC LIMIT ?"), filtered);

        params.put("decision", null);
        assertFalse(sql(DatasetAccessAuditMapper.class, "find", params).contains("decision ="));
    }

    @Test
    void activeGrantQueriesTreatExpiryAsExclusive() {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", 7L);
        params.put("now", LocalDateTime.of(2026, 9, 24, 8, 0));
        params.put("datasetIds", Arrays.asList(1L, 2L));

        String byUser = sql(DatasetAccessGrantMapper.class, "findActiveByUser", params);
        assertTrue(byUser.contains("FROM dataset_access_grant WHERE user_id = ? AND expires_at > ?"), byUser);

        String byDatasets = sql(DatasetAccessGrantMapper.class, "findActiveByUserAndDatasets", params);
        assertTrue(byDatasets.contains("WHERE user_id = ? AND expires_at > ? AND dataset_id IN"), byDatasets);
        assertTrue(byDatasets.replace(" ", "").contains("IN(?,?)"), byDatasets);

        String live = sql(DatasetAccessGrantMapper.class, "findLiveDatasets", params);
        assertTrue(live.startsWith("SELECT dataset_id, dataset_version FROM registered_dataset "
                + "WHERE deleted_at IS NULL AND dataset_id IN"), live);
        assertFalse(live.contains("owner_domain_id"), "ownership must not feed the usage decision: " + live);

        String lock = sql(DatasetAccessGrantMapper.class, "lockUser", params);
        assertTrue(lock.endsWith("FOR UPDATE"), lock);
    }

    @Test
    void grantLogJoinsApplicantAndDatasetNewestFirst() {
        Map<String, Object> params = new HashMap<>();
        params.put("limit", 500);

        String log = sql(DatasetAccessGrantMapper.class, "findGrantLog", params);
        assertTrue(log.contains("FROM dataset_access_grant g LEFT JOIN app_user u ON u.user_id = g.user_id"), log);
        assertTrue(log.contains("LEFT JOIN collaboration_domain d ON d.domain_id = u.domain_id"), log);
        assertTrue(log.contains("LEFT JOIN registered_dataset rd ON rd.dataset_id = g.dataset_id"), log);
        assertTrue(log.endsWith("ORDER BY g.grant_id DESC LIMIT ?"), log);
    }

    @Test
    void grantInsertWritesBothUtcTimestampsExplicitly() {
        String insert = sql(DatasetAccessGrantMapper.class, "insert", new DatasetAccessGrant());
        assertTrue(insert.contains("(user_id, dataset_id, reason, created_at, expires_at)"), insert);
        assertTrue(insert.contains("VALUES (?, ?, ?, ?, ?)"), insert);
    }

    @Test
    void locationDomainsFollowLocatedReplicasThroughNodeSitesToEnabledDomains() {
        String expected = "SELECT DISTINCT r.dataset_id, d.domain_id, d.domain_code, d.name AS domain_name "
                + "FROM dataset_replica r "
                + "JOIN registered_dataset rd ON rd.dataset_id = r.dataset_id AND rd.deleted_at IS NULL "
                + "JOIN node_management n ON n.node_id = r.node_id AND n.deleted_at IS NULL "
                + "JOIN collaboration_domain d ON d.site_code = n.site_code AND d.enabled = 1 "
                + "WHERE r.availability NOT IN ('MISSING', 'VERIFY_FAILED') ";

        String all = sql(DatasetDomainMapper.class, "findAllLocationDomains", Collections.emptyMap());
        assertEquals(expected + "ORDER BY r.dataset_id, d.domain_id", all);
        assertFalse(all.contains("owner_domain_id"), all);

        Map<String, Object> params = new HashMap<>();
        params.put("datasetIds", Arrays.asList(4L, 5L, 6L));
        String some = sql(DatasetDomainMapper.class, "findLocationDomains", params);
        assertTrue(some.startsWith(expected + "AND r.dataset_id IN"), some);
        assertTrue(some.replace(" ", "").endsWith("IN(?,?,?)ORDERBYr.dataset_id,d.domain_id"), some);
    }

    private String sql(Class<?> mapper, String statement, Object params) {
        return configuration.getMappedStatement(mapper.getName() + "." + statement)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}
