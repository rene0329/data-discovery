package org.example.privacy;

import org.apache.ibatis.session.Configuration;
import org.example.privacy.PrivacyComputeModels.InputSnapshotRecord;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Approvals and visibility are keyed by the participant's domain, not by a dataset holder. */
class PrivacyComputeMapperSqlTest {
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        configuration.addMapper(PrivacyComputeMapper.class);
    }

    @Test
    void pendingApprovalsAreTheCurrentAttemptsWaitingForTheDomain() {
        Map<String, Object> params = new HashMap<>();
        params.put("domainId", 2L);
        params.put("limit", 50);

        String sql = sql("listPendingApprovals", params);

        assertTrue(sql.startsWith("SELECT j.* FROM privacy_compute_job j WHERE j.status='AWAITING_APPROVAL' "
                + "AND EXISTS (SELECT 1 FROM privacy_compute_approval a JOIN privacy_compute_participant p "
                + "ON p.job_id=a.job_id AND p.party_id=a.participant_id "
                + "WHERE a.job_id=j.job_id AND a.attempt_id=j.current_attempt_id AND a.decision='PENDING' "
                + "AND p.owner_domain_id=?)"), sql);
        assertFalse(sql.contains("approver_user_id"), sql);
        assertFalse(sql.contains("owner_user_id"), sql);
    }

    @Test
    void participantJobListMatchesTheInitiatorOrTheDomain() {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", 7L);
        params.put("domainId", 2L);
        params.put("status", "QUEUED");
        params.put("limit", 50);

        // Dynamic SQL joins its fragments with spaces; compare without them.
        assertEquals(compact("SELECT j.* FROM privacy_compute_job j WHERE (j.initiator_user_id=? OR EXISTS "
                        + "(SELECT 1 FROM privacy_compute_participant p WHERE p.job_id=j.job_id "
                        + "AND p.owner_domain_id=?)) AND j.status=? ORDER BY j.created_at DESC LIMIT ?"),
                compact(sql("listJobsForParticipant", params)));

        params.put("domainId", null);
        params.put("status", null);
        assertEquals(compact("SELECT j.* FROM privacy_compute_job j WHERE (j.initiator_user_id=?) "
                + "ORDER BY j.created_at DESC LIMIT ?"), compact(sql("listJobsForParticipant", params)));
    }

    @Test
    void aDecisionRecordsTheDeciderWithoutRequiringAPreassignedApprover() {
        Map<String, Object> params = new HashMap<>();
        for (String key : new String[]{"jobId", "attemptId", "participantId", "decision", "reason",
                "decisionSignature", "approverUsername"}) {
            params.put(key, "x");
        }
        params.put("approverUserId", 4L);

        String decide = sql("decide", params);

        assertTrue(decide.contains("approver_user_id=?,approver_username=?"), decide);
        assertTrue(decide.endsWith("WHERE job_id=? AND attempt_id=? AND participant_id=? AND decision='PENDING'"),
                decide);
    }

    @Test
    void approvalsReportTheDomainAndNoApproverWhilePending() {
        Map<String, Object> params = new HashMap<>();
        params.put("jobId", "pcj-1");
        params.put("attemptId", "pca-1");

        String sql = sql("findApprovals", params);

        assertTrue(sql.contains("p.owner_domain_id,p.owner_domain_code"), sql);
        assertTrue(sql.contains("CASE WHEN a.decision='PENDING' THEN NULL ELSE a.approver_user_id END approver_user_id"),
                sql);
        assertTrue(sql.contains("LEFT JOIN privacy_compute_participant p ON p.job_id=a.job_id "
                + "AND p.party_id=a.participant_id"), sql);
    }

    @Test
    void participantsAndSnapshotsNoLongerStoreAHolder() {
        Map<String, Object> participant = new HashMap<>();
        participant.put("jobId", "pcj-1");
        participant.put("p", new ParticipantSpec());
        String insertParticipant = sql("insertParticipant", participant);
        assertTrue(insertParticipant.contains("(job_id,party_id,slot_id,role,owner_domain_id,owner_domain_code,created_at)"),
                insertParticipant);
        assertFalse(insertParticipant.contains("owner_user"), insertParticipant);

        String insertSnapshot = sql("insertInputSnapshot", new InputSnapshotRecord());
        assertTrue(insertSnapshot.contains("owner_domain_id"), insertSnapshot);
        assertFalse(insertSnapshot.contains("owner_user_id"), insertSnapshot);

        Map<String, Object> domain = new HashMap<>();
        domain.put("jobId", "pcj-1");
        domain.put("domainId", 2L);
        assertEquals("SELECT * FROM privacy_compute_participant WHERE job_id=? AND owner_domain_id=? "
                + "ORDER BY party_id LIMIT 1", sql("findParticipantForDomain", domain));
    }

    private static String compact(String sql) {
        return sql.replace(" ", "");
    }

    private String sql(String statement, Object params) {
        return configuration.getMappedStatement(PrivacyComputeMapper.class.getName() + "." + statement)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}
