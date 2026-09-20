package org.example.privacy;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.privacy.PrivacyComputeModels.ApprovalRecord;
import org.example.privacy.PrivacyComputeModels.EventRecord;
import org.example.privacy.PrivacyComputeModels.DatasetOwnershipRecord;
import org.example.privacy.PrivacyComputeModels.EvidenceRecord;
import org.example.privacy.PrivacyComputeModels.InputSnapshotRecord;
import org.example.privacy.PrivacyComputeModels.JobRecord;
import org.example.privacy.PrivacyComputeModels.ParticipantSpec;
import org.example.privacy.PrivacyComputeModels.ResultRecord;

import java.util.List;

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
            "(job_id,party_id,slot_id,role,owner_user_id,owner_username,owner_domain_id,owner_domain_code,created_at) " +
            "VALUES (#{jobId},#{p.partyId},#{p.slotId},#{p.role},#{p.ownerUserId},#{p.ownerUsername}," +
            "#{p.ownerDomainId},#{p.ownerDomainCode},UTC_TIMESTAMP(3))")
    int insertParticipant(@Param("jobId") String jobId, @Param("p") ParticipantSpec participant);

    @Insert("INSERT INTO privacy_compute_input_snapshot " +
            "(job_id,party_id,slot_id,owner_user_id,owner_domain_id,dataset_id,dataset_code,dataset_version,digest_algorithm,digest_value," +
            "size_bytes,schema_json,schema_digest,fields_json,created_at) VALUES " +
            "(#{jobId},#{partyId},#{slotId},#{ownerUserId},#{ownerDomainId},#{datasetId},#{datasetCode},#{datasetVersion},#{digestAlgorithm}," +
            "#{digestValue},#{sizeBytes},#{schemaJson},#{schemaDigest},#{fieldsJson},UTC_TIMESTAMP(3))")
    int insertInputSnapshot(InputSnapshotRecord snapshot);

    @Insert("INSERT INTO privacy_compute_attempt " +
            "(attempt_id,job_id,attempt_no,status,random_context_id,created_at) VALUES " +
            "(#{attemptId},#{jobId},#{attemptNo},'AWAITING_APPROVAL',#{randomContextId},UTC_TIMESTAMP(3))")
    int insertAttempt(@Param("attemptId") String attemptId, @Param("jobId") String jobId,
                      @Param("attemptNo") int attemptNo, @Param("randomContextId") String randomContextId);

    @Insert("INSERT INTO privacy_compute_approval " +
            "(job_id,attempt_id,participant_id,approver_user_id,approver_username,input_snapshot_digest," +
            "decision,decision_signature,created_at,decided_at) VALUES " +
            "(#{jobId},#{attemptId},#{participantId},#{ownerUserId},#{ownerUsername},#{snapshotDigest},#{decision}," +
            "#{decisionSignature},UTC_TIMESTAMP(3),CASE WHEN #{decision}='APPROVED' THEN UTC_TIMESTAMP(3) ELSE NULL END)")
    int insertPendingApproval(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
                              @Param("participantId") String participantId,
                              @Param("ownerUserId") Long ownerUserId,
                              @Param("ownerUsername") String ownerUsername,
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

    @Select({"<script>", "SELECT DISTINCT j.* FROM privacy_compute_job j",
            "LEFT JOIN privacy_compute_participant p ON p.job_id=j.job_id",
            "WHERE (j.initiator_user_id=#{userId} OR p.owner_user_id=#{userId})",
            "<if test='status != null and status != \"\"'> AND j.status=#{status}</if>",
            "ORDER BY j.created_at DESC LIMIT #{limit}", "</script>"})
    List<JobRecord> listJobsForParticipant(@Param("userId") Long userId,
                                           @Param("status") String status,
                                           @Param("limit") int limit);

    @Select("SELECT party_id FROM privacy_compute_participant WHERE job_id=#{jobId} ORDER BY party_id")
    List<String> findParticipantIds(@Param("jobId") String jobId);

    @Select("SELECT * FROM privacy_compute_participant WHERE job_id=#{jobId} ORDER BY party_id")
    List<ParticipantSpec> findParticipants(@Param("jobId") String jobId);

    @Select("SELECT * FROM privacy_compute_participant WHERE job_id=#{jobId} AND owner_user_id=#{userId} LIMIT 1")
    ParticipantSpec findParticipantForOwner(@Param("jobId") String jobId, @Param("userId") Long userId);

    @Select("SELECT j.* FROM privacy_compute_job j JOIN privacy_compute_approval a " +
            "ON a.job_id=j.job_id AND a.attempt_id=j.current_attempt_id " +
            "WHERE a.approver_user_id=#{userId} AND a.decision='PENDING' AND j.status='AWAITING_APPROVAL' " +
            "ORDER BY j.created_at DESC LIMIT #{limit}")
    List<JobRecord> listPendingApprovals(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT d.dataset_id,d.owner_user_id,u.username owner_username," +
            "u.enabled owner_enabled," +
            "d.owner_domain_id,c.domain_code owner_domain_code," +
            "c.enabled domain_enabled " +
            "FROM registered_dataset d LEFT JOIN app_user u ON u.user_id=d.owner_user_id " +
            "LEFT JOIN collaboration_domain c ON c.domain_id=d.owner_domain_id WHERE d.dataset_id=#{datasetId}")
    DatasetOwnershipRecord findDatasetOwnership(@Param("datasetId") Long datasetId);

    @Select("SELECT COUNT(*) FROM app_user WHERE user_id=#{userId} AND enabled=TRUE")
    int countEnabledUser(@Param("userId") Long userId);

    @Select("SELECT * FROM privacy_compute_input_snapshot WHERE job_id=#{jobId} ORDER BY party_id")
    List<InputSnapshotRecord> findInputSnapshots(@Param("jobId") String jobId);

    @Update("UPDATE privacy_compute_approval SET decision=#{decision},reason=#{reason}," +
            "decision_signature=#{decisionSignature},approver_username=#{approverUsername},decided_at=UTC_TIMESTAMP(3) " +
            "WHERE job_id=#{jobId} AND attempt_id=#{attemptId} AND participant_id=#{participantId} " +
            "AND approver_user_id=#{approverUserId} AND decision='PENDING'")
    int decide(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
               @Param("participantId") String participantId, @Param("decision") String decision,
               @Param("reason") String reason, @Param("decisionSignature") String decisionSignature,
               @Param("approverUserId") Long approverUserId,
               @Param("approverUsername") String approverUsername);

    @Select("SELECT * FROM privacy_compute_approval WHERE job_id=#{jobId} " +
            "AND attempt_id=#{attemptId} ORDER BY participant_id")
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
