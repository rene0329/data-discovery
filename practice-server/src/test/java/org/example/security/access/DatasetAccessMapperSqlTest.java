package org.example.security.access;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetAccessMapperSqlTest {
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        configuration.addMapper(DatasetAccessAuditMapper.class);
        configuration.addMapper(DatasetAccessGrantMapper.class);
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

        String ownership = sql(DatasetAccessGrantMapper.class, "findDatasetOwnership", params);
        assertTrue(ownership.contains("FROM registered_dataset WHERE deleted_at IS NULL AND dataset_id IN"),
                ownership);

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

    private String sql(Class<?> mapper, String statement, Object params) {
        return configuration.getMappedStatement(mapper.getName() + "." + statement)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}
