package org.example.privacy;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.privacy.PrivacyComputeModels.ApprovalRecord;
import org.example.privacy.PrivacyComputeModels.EventRecord;
import org.example.privacy.PrivacyComputeModels.EvidenceRecord;
import org.example.privacy.PrivacyComputeModels.InputSnapshotRecord;
import org.example.privacy.PrivacyComputeModels.JobRecord;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.ResultRecord;

import java.util.List;

/**
 * Privacy control-plane persistence. A participant's party is a collaboration
 * domain (privacy_compute_participant.owner_domain_id / owner_domain_code, frozen
 * at creation from the dataset's location); approvals are addressed to that
 * domain, and approver_user_id / approver_username record who actually decided.
 * The owner_user_id / owner_username columns of the retired per-dataset holder
 * are no longer written or read.
 */
@Mapper
public interface PrivacyComputeMapper {
    @Insert("INSERT INTO privacy_compute_job (job_id,request_id,current_attempt_id,current_attempt_no," +
            "template_id,provider,security_profile,status,initiator,initiator_user_id,result_recipients_json,timeout_seconds," +
            "engine_policy_json,spec_json,spec_digest,protocol_version,image_digest,created_at) VALUES " +
            "(#{jobId},#{requestId},#{currentAttemptId},#{currentAttemptNo},#{templateId},#{provider}," +
            "#{securityProfile},#{status},#{initiator},#{initiatorUserId},#{resultRecipientsJson},#{timeoutSeconds}," +
            "#{enginePolicyJson},#{specJson},#{specDigest},#{protocolVersion},#{imageDigest},UTC_TIMESTAMP(3))")
    int insertJob(JobRecord job);

    @Insert("INSERT INTO privacy_compute_participant " +
            "(job_id,party_id,slot_id,role,owner_domain_id,owner_domain_code,created_at) " +
            "VALUES (#{jobId},#{p.partyId},#{p.slotId},#{p.role}," +
            "#{p.ownerDomainId},#{p.ownerDomainCode},UTC_TIMESTAMP(3))")
    int insertParticipant(@Param("jobId") String jobId, @Param("p") ParticipantSpec participant);

    @Insert("INSERT INTO privacy_compute_input_snapshot " +
            "(job_id,party_id,slot_id,owner_domain_id,dataset_id,dataset_code,dataset_version,digest_algorithm,digest_value," +
            "size_bytes,schema_json,schema_digest,fields_json,created_at) VALUES " +
            "(#{jobId},#{partyId},#{slotId},#{ownerDomainId},#{datasetId},#{datasetCode},#{datasetVersion},#{digestAlgorithm}," +
            "#{digestValue},#{sizeBytes},#{schemaJson},#{schemaDigest},#{fieldsJson},UTC_TIMESTAMP(3))")
    int insertInputSnapshot(InputSnapshotRecord snapshot);

    @Insert("INSERT INTO privacy_compute_attempt " +
            "(attempt_id,job_id,attempt_no,status,random_context_id,created_at) VALUES " +
            "(#{attemptId},#{jobId},#{attemptNo},'AWAITING_APPROVAL',#{randomContextId},UTC_TIMESTAMP(3))")
    int insertAttempt(@Param("attemptId") String attemptId, @Param("jobId") String jobId,
                      @Param("attemptNo") int attemptNo, @Param("randomContextId") String randomContextId);

    /** approverUserId/approverUsername: the initiator for an auto-approved row, otherwise null. */
    @Insert("INSERT INTO privacy_compute_approval " +
            "(job_id,attempt_id,participant_id,approver_user_id,approver_username,input_snapshot_digest," +
            "decision,decision_signature,created_at,decided_at) VALUES " +
            "(#{jobId},#{attemptId},#{participantId},#{approverUserId},#{approverUsername},#{snapshotDigest},#{decision}," +
            "#{decisionSignature},UTC_TIMESTAMP(3),CASE WHEN #{decision}='APPROVED' THEN UTC_TIMESTAMP(3) ELSE NULL END)")
    int insertPendingApproval(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
                              @Param("participantId") String participantId,
                              @Param("approverUserId") Long approverUserId,
                              @Param("approverUsername") String approverUsername,
                              @Param("snapshotDigest") String snapshotDigest,
                              @Param("decision") String decision,
                              @Param("decisionSignature") String decisionSignature);

    @Select("SELECT * FROM privacy_compute_job WHERE job_id=#{jobId}")
    JobRecord findJob(@Param("jobId") String jobId);

    @Select("SELECT * FROM privacy_compute_job WHERE request_id=#{requestId} LIMIT 1")
    JobRecord findJobByRequestId(@Param("requestId") String requestId);

    @Select({"<script>", "SELECT * FROM privacy_compute_job",
            "<if test='status != null and status != \"\"'> WHERE status=#{status}</if>",
            "ORDER BY created_at DESC LIMIT #{limit}", "</script>"})
    List<JobRecord> listJobs(@Param("status") String status, @Param("limit") int limit);

    /** Jobs the user initiated or in which the user's domain (null: none) is a participant. */
    @Select({"<script>", "SELECT j.* FROM privacy_compute_job j",
            "WHERE (j.initiator_user_id=#{userId}",
            "<if test='domainId != null'> OR EXISTS (SELECT 1 FROM privacy_compute_participant p",
            "WHERE p.job_id=j.job_id AND p.owner_domain_id=#{domainId})</if>)",
            "<if test='status != null and status != \"\"'> AND j.status=#{status}</if>",
            "ORDER BY j.created_at DESC LIMIT #{limit}", "</script>"})
    List<JobRecord> listJobsForParticipant(@Param("userId") Long userId,
                                           @Param("domainId") Long domainId,
                                           @Param("status") String status,
                                           @Param("limit") int limit);

    @Select("SELECT party_id FROM privacy_compute_participant WHERE job_id=#{jobId} ORDER BY party_id")
    List<String> findParticipantIds(@Param("jobId") String jobId);

    @Select("SELECT * FROM privacy_compute_participant WHERE job_id=#{jobId} ORDER BY party_id")
    List<ParticipantSpec> findParticipants(@Param("jobId") String jobId);

    /** The job's participant contributed by {@code domainId}; the resolver keeps domains distinct per job. */
    @Select("SELECT * FROM privacy_compute_participant WHERE job_id=#{jobId} AND owner_domain_id=#{domainId} " +
            "ORDER BY party_id LIMIT 1")
    ParticipantSpec findParticipantForDomain(@Param("jobId") String jobId, @Param("domainId") Long domainId);

    /** 待我审批: jobs whose current attempt still waits for a participant of {@code domainId}. */
    @Select("SELECT j.* FROM privacy_compute_job j WHERE j.status='AWAITING_APPROVAL' AND EXISTS (" +
            "SELECT 1 FROM privacy_compute_approval a JOIN privacy_compute_participant p " +
            "ON p.job_id=a.job_id AND p.party_id=a.participant_id " +
            "WHERE a.job_id=j.job_id AND a.attempt_id=j.current_attempt_id AND a.decision='PENDING' " +
            "AND p.owner_domain_id=#{domainId}) " +
            "ORDER BY j.created_at DESC LIMIT #{limit}")
    List<JobRecord> listPendingApprovals(@Param("domainId") Long domainId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM app_user WHERE user_id=#{userId} AND enabled=TRUE")
    int countEnabledUser(@Param("userId") Long userId);

    @Select("SELECT * FROM privacy_compute_input_snapshot WHERE job_id=#{jobId} ORDER BY party_id")
    List<InputSnapshotRecord> findInputSnapshots(@Param("jobId") String jobId);

    /** Records the decider; the caller has already checked it is a domain user of the participant. */
    @Update("UPDATE privacy_compute_approval SET decision=#{decision},reason=#{reason}," +
            "decision_signature=#{decisionSignature},approver_user_id=#{approverUserId}," +
            "approver_username=#{approverUsername},decided_at=UTC_TIMESTAMP(3) " +
            "WHERE job_id=#{jobId} AND attempt_id=#{attemptId} AND participant_id=#{participantId} " +
            "AND decision='PENDING'")
    int decide(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
               @Param("participantId") String participantId, @Param("decision") String decision,
               @Param("reason") String reason, @Param("decisionSignature") String decisionSignature,
               @Param("approverUserId") Long approverUserId,
               @Param("approverUsername") String approverUsername);

    /**
     * Approvals with their participant's domain. Rows created before the domain
     * model pre-filled approver_* with the dataset holder while still PENDING, so
     * the approver is reported only once a decision exists.
     */
    @Select("SELECT a.job_id,a.attempt_id,a.participant_id,p.owner_domain_id,p.owner_domain_code," +
            "CASE WHEN a.decision='PENDING' THEN NULL ELSE a.approver_user_id END approver_user_id," +
            "CASE WHEN a.decision='PENDING' THEN NULL ELSE a.approver_username END approver_username," +
            "a.input_snapshot_digest,a.decision,a.reason,a.decision_signature,a.decided_at " +
            "FROM privacy_compute_approval a LEFT JOIN privacy_compute_participant p " +
            "ON p.job_id=a.job_id AND p.party_id=a.participant_id " +
            "WHERE a.job_id=#{jobId} AND a.attempt_id=#{attemptId} ORDER BY a.participant_id")
    List<ApprovalRecord> findApprovals(@Param("jobId") String jobId,
                                       @Param("attemptId") String attemptId);

    @Update("UPDATE privacy_compute_job SET status=#{toStatus}," +
            "queued_at=CASE WHEN #{toStatus}='QUEUED' THEN UTC_TIMESTAMP(3) ELSE queued_at END," +
            "started_at=CASE WHEN #{toStatus}='RUNNING' THEN UTC_TIMESTAMP(3) ELSE started_at END," +
            "completed_at=CASE WHEN #{toStatus} IN ('SUCCEEDED','FAILED','ABORTED','CANCELLED') " +
            "THEN UTC_TIMESTAMP(3) ELSE completed_at END WHERE job_id=#{jobId} " +
            "AND current_attempt_id=#{attemptId} AND status=#{fromStatus}")
    int transition(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
                   @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    @Update("UPDATE privacy_compute_job j SET j.status='QUEUED',j.queued_at=UTC_TIMESTAMP(3) " +
            "WHERE j.job_id=#{jobId} AND j.current_attempt_id=#{attemptId} " +
            "AND j.status='AWAITING_APPROVAL' " +
            "AND EXISTS (SELECT 1 FROM privacy_compute_approval a " +
            "WHERE a.job_id=j.job_id AND a.attempt_id=j.current_attempt_id) " +
            "AND NOT EXISTS (SELECT 1 FROM privacy_compute_approval a " +
            "WHERE a.job_id=j.job_id AND a.attempt_id=j.current_attempt_id " +
            "AND a.decision!='APPROVED')")
    int queueIfFullyApproved(@Param("jobId") String jobId, @Param("attemptId") String attemptId);

    @Update("UPDATE privacy_compute_attempt SET status=#{status}," +
            "queued_at=CASE WHEN #{status}='QUEUED' THEN UTC_TIMESTAMP(3) ELSE queued_at END," +
            "started_at=CASE WHEN #{status}='RUNNING' THEN UTC_TIMESTAMP(3) ELSE started_at END," +
            "completed_at=CASE WHEN #{status} IN ('SUCCEEDED','FAILED','ABORTED','CANCELLED') " +
            "THEN UTC_TIMESTAMP(3) ELSE completed_at END WHERE attempt_id=#{attemptId}")
    int updateAttemptStatus(@Param("attemptId") String attemptId, @Param("status") String status);

    @Update("UPDATE privacy_compute_job SET external_job_id=#{externalJobId} WHERE job_id=#{jobId} " +
            "AND current_attempt_id=#{attemptId}")
    int setExternalJobId(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
                         @Param("externalJobId") String externalJobId);

    @Update("UPDATE privacy_compute_attempt SET external_job_id=#{externalJobId} WHERE attempt_id=#{attemptId}")
    int setAttemptExternalJobId(@Param("attemptId") String attemptId,
                                @Param("externalJobId") String externalJobId);

    @Update("UPDATE privacy_compute_job SET status=#{status},failure_code=#{failureCode}," +
            "failure_reason=#{failureReason},completed_at=UTC_TIMESTAMP(3) WHERE job_id=#{jobId} " +
            "AND current_attempt_id=#{attemptId} AND status NOT IN ('SUCCEEDED','FAILED','ABORTED','CANCELLED')")
    int finish(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
               @Param("status") String status, @Param("failureCode") String failureCode,
               @Param("failureReason") String failureReason);

    @Update("UPDATE privacy_compute_attempt SET status=#{status},failure_code=#{failureCode}," +
            "failure_reason=#{failureReason},completed_at=UTC_TIMESTAMP(3) WHERE attempt_id=#{attemptId}")
    int finishAttempt(@Param("attemptId") String attemptId, @Param("status") String status,
                      @Param("failureCode") String failureCode,
                      @Param("failureReason") String failureReason);

    @Update("UPDATE privacy_compute_job SET current_attempt_id=#{attemptId},current_attempt_no=#{attemptNo}," +
            "status='AWAITING_APPROVAL',external_job_id=NULL,failure_code=NULL,failure_reason=NULL," +
            "queued_at=NULL,started_at=NULL,completed_at=NULL WHERE job_id=#{jobId} " +
            "AND current_attempt_no=#{previousAttemptNo} AND status IN ('FAILED','ABORTED','CANCELLED')")
    int beginRetry(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
                   @Param("attemptNo") int attemptNo, @Param("previousAttemptNo") int previousAttemptNo);

    @Insert("INSERT INTO privacy_compute_event (job_id,attempt_id,participant_id,phase,status," +
            "message_code,payload_bytes,message_digest,detail,created_at) VALUES " +
            "(#{jobId},#{attemptId},#{participantId},#{phase},#{status},#{messageCode}," +
            "#{payloadBytes},#{messageDigest},#{detail},UTC_TIMESTAMP(3))")
    int insertEvent(EventRecord event);

    @Select("SELECT * FROM privacy_compute_event WHERE job_id=#{jobId} ORDER BY event_id")
    List<EventRecord> findEvents(@Param("jobId") String jobId);

    @Insert("INSERT INTO privacy_compute_result (job_id,attempt_id,result_reference,result_digest," +
            "media_type,created_at) VALUES (#{jobId},#{attemptId},#{resultReference},#{resultDigest}," +
            "#{mediaType},UTC_TIMESTAMP(3))")
    int insertResult(ResultRecord result);

    @Select("SELECT * FROM privacy_compute_result WHERE job_id=#{jobId} AND attempt_id=#{attemptId} LIMIT 1")
    ResultRecord findResult(@Param("jobId") String jobId, @Param("attemptId") String attemptId);

    @Insert("INSERT INTO privacy_compute_evidence (job_id,attempt_id,evidence_reference,evidence_json," +
            "evidence_digest,created_at) VALUES (#{jobId},#{attemptId},#{evidenceReference}," +
            "#{evidenceJson},#{evidenceDigest},UTC_TIMESTAMP(3))")
    int insertEvidence(EvidenceRecord evidence);

    @Select("SELECT * FROM privacy_compute_evidence WHERE job_id=#{jobId} AND attempt_id=#{attemptId} LIMIT 1")
    EvidenceRecord findEvidence(@Param("jobId") String jobId, @Param("attemptId") String attemptId);
}
