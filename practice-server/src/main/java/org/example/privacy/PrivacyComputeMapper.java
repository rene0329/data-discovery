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

@Mapper
public interface PrivacyComputeMapper {
    @Insert("INSERT INTO privacy_compute_job (job_id,request_id,current_attempt_id,current_attempt_no," +
            "template_id,provider,security_profile,status,initiator,result_recipients_json,timeout_seconds," +
            "engine_policy_json,spec_json,spec_digest,protocol_version,image_digest,created_at) VALUES " +
            "(#{jobId},#{requestId},#{currentAttemptId},#{currentAttemptNo},#{templateId},#{provider}," +
            "#{securityProfile},#{status},#{initiator},#{resultRecipientsJson},#{timeoutSeconds}," +
            "#{enginePolicyJson},#{specJson},#{specDigest},#{protocolVersion},#{imageDigest},UTC_TIMESTAMP(3))")
    int insertJob(JobRecord job);

    @Insert("INSERT INTO privacy_compute_participant " +
            "(job_id,party_id,role,created_at) VALUES (#{jobId},#{p.partyId},#{p.role},UTC_TIMESTAMP(3))")
    int insertParticipant(@Param("jobId") String jobId, @Param("p") ParticipantSpec participant);

    @Insert("INSERT INTO privacy_compute_input_snapshot " +
            "(job_id,party_id,dataset_id,dataset_code,dataset_version,digest_algorithm,digest_value," +
            "size_bytes,schema_json,schema_digest,fields_json,created_at) VALUES " +
            "(#{jobId},#{partyId},#{datasetId},#{datasetCode},#{datasetVersion},#{digestAlgorithm}," +
            "#{digestValue},#{sizeBytes},#{schemaJson},#{schemaDigest},#{fieldsJson},UTC_TIMESTAMP(3))")
    int insertInputSnapshot(InputSnapshotRecord snapshot);

    @Insert("INSERT INTO privacy_compute_attempt " +
            "(attempt_id,job_id,attempt_no,status,random_context_id,created_at) VALUES " +
            "(#{attemptId},#{jobId},#{attemptNo},'AWAITING_APPROVAL',#{randomContextId},UTC_TIMESTAMP(3))")
    int insertAttempt(@Param("attemptId") String attemptId, @Param("jobId") String jobId,
                      @Param("attemptNo") int attemptNo, @Param("randomContextId") String randomContextId);

    @Insert("INSERT INTO privacy_compute_approval " +
            "(job_id,attempt_id,participant_id,decision,created_at) VALUES " +
            "(#{jobId},#{attemptId},#{participantId},'PENDING',UTC_TIMESTAMP(3))")
    int insertPendingApproval(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
                              @Param("participantId") String participantId);

    @Select("SELECT * FROM privacy_compute_job WHERE job_id=#{jobId}")
    JobRecord findJob(@Param("jobId") String jobId);

    @Select("SELECT * FROM privacy_compute_job WHERE request_id=#{requestId} LIMIT 1")
    JobRecord findJobByRequestId(@Param("requestId") String requestId);

    @Select({"<script>", "SELECT * FROM privacy_compute_job",
            "<if test='status != null and status != \"\"'> WHERE status=#{status}</if>",
            "ORDER BY created_at DESC LIMIT #{limit}", "</script>"})
    List<JobRecord> listJobs(@Param("status") String status, @Param("limit") int limit);

    @Select({"<script>", "SELECT j.* FROM privacy_compute_job j",
            "JOIN privacy_compute_participant p ON p.job_id=j.job_id",
            "WHERE p.party_id=#{partyId}",
            "<if test='status != null and status != \"\"'> AND j.status=#{status}</if>",
            "ORDER BY j.created_at DESC LIMIT #{limit}", "</script>"})
    List<JobRecord> listJobsForParticipant(@Param("partyId") String partyId,
                                           @Param("status") String status,
                                           @Param("limit") int limit);

    @Select("SELECT party_id FROM privacy_compute_participant WHERE job_id=#{jobId} ORDER BY party_id")
    List<String> findParticipantIds(@Param("jobId") String jobId);

    @Select("SELECT * FROM privacy_compute_input_snapshot WHERE job_id=#{jobId} ORDER BY party_id")
    List<InputSnapshotRecord> findInputSnapshots(@Param("jobId") String jobId);

    @Update("UPDATE privacy_compute_approval SET decision=#{decision},reason=#{reason}," +
            "decision_signature=#{decisionSignature},decided_at=UTC_TIMESTAMP(3) WHERE job_id=#{jobId} " +
            "AND attempt_id=#{attemptId} AND participant_id=#{participantId} AND decision='PENDING'")
    int decide(@Param("jobId") String jobId, @Param("attemptId") String attemptId,
               @Param("participantId") String participantId, @Param("decision") String decision,
               @Param("reason") String reason, @Param("decisionSignature") String decisionSignature);

    @Select("SELECT COUNT(*) FROM privacy_compute_approval WHERE job_id=#{jobId} " +
            "AND attempt_id=#{attemptId} AND decision='APPROVED'")
    int countApproved(@Param("jobId") String jobId, @Param("attemptId") String attemptId);

    @Select("SELECT COUNT(*) FROM privacy_compute_approval WHERE job_id=#{jobId} AND attempt_id=#{attemptId}")
    int countApprovals(@Param("jobId") String jobId, @Param("attemptId") String attemptId);

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
