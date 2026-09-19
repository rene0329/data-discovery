package org.example.security.aggregation.coordinator;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SecureAggregationMapper {
    @Insert("INSERT INTO secure_aggregation_run " +
            "(run_id, request_id, status, protocol_version, participants_json, created_at) " +
            "VALUES (#{runId}, #{requestId}, 'PENDING', #{protocolVersion}, #{participantsJson}, UTC_TIMESTAMP(3))")
    int insertRun(SecureAggregationRun run);

    @Update("UPDATE secure_aggregation_run SET status='RUNNING', started_at=UTC_TIMESTAMP(3) " +
            "WHERE run_id=#{runId} AND status='PENDING'")
    int markRunning(@Param("runId") String runId);

    @Update("UPDATE secure_aggregation_run SET status='COMPLETED', final_value=#{finalValue}, " +
            "failure_reason=NULL, completed_at=UTC_TIMESTAMP(3) WHERE run_id=#{runId} AND status='RUNNING'")
    int markCompleted(@Param("runId") String runId, @Param("finalValue") String finalValue);

    @Update("UPDATE secure_aggregation_run SET status='FAILED', final_value=NULL, " +
            "failure_reason=#{reason}, completed_at=UTC_TIMESTAMP(3) WHERE run_id=#{runId} " +
            "AND status IN ('PENDING','RUNNING')")
    int markFailed(@Param("runId") String runId, @Param("reason") String reason);

    @Select("SELECT run_id, request_id, status, protocol_version, participants_json, final_value, " +
            "failure_reason, started_at, completed_at, created_at FROM secure_aggregation_run " +
            "WHERE run_id=#{runId}")
    SecureAggregationRun findRun(@Param("runId") String runId);

    @Select("SELECT run_id, request_id, status, protocol_version, participants_json, final_value, " +
            "failure_reason, started_at, completed_at, created_at FROM secure_aggregation_run " +
            "WHERE request_id=#{requestId} LIMIT 1")
    SecureAggregationRun findRunByRequestId(@Param("requestId") String requestId);

    @Insert("INSERT INTO secure_aggregation_message_event " +
            "(run_id, participant_id, direction, message_type, status, payload_bytes, error_code, created_at) " +
            "VALUES (#{runId}, #{participantId}, #{direction}, #{messageType}, #{status}, " +
            "#{payloadBytes}, #{errorCode}, UTC_TIMESTAMP(3))")
    int insertEvent(SecureAggregationMessageEvent event);

    @Select("SELECT event_id, run_id, participant_id, direction, message_type, status, " +
            "payload_bytes, error_code, created_at FROM secure_aggregation_message_event " +
            "WHERE run_id=#{runId} ORDER BY event_id")
    List<SecureAggregationMessageEvent> findEvents(@Param("runId") String runId);
}
