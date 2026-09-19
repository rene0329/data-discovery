package org.example.security.access;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DatasetAccessAuditMapper {
    @Insert("INSERT INTO dataset_access_audit_event " +
            "(request_id, run_id, jti, principal, dataset_id, dataset_version, file_path, " +
            "action, target_node, policy_rule, decision, reason, token_expires_at, client_ip, created_at) " +
            "VALUES (#{requestId}, #{runId}, #{jti}, #{principal}, #{datasetId}, #{datasetVersion}, " +
            "#{filePath}, #{action}, #{targetNode}, #{policyRule}, #{decision}, #{reason}, " +
            "#{tokenExpiresAt}, #{clientIp}, UTC_TIMESTAMP(3))")
    int insert(DatasetAccessAuditEvent event);

    @Select({"<script>",
            "SELECT event_id, request_id, run_id, jti, principal, dataset_id, dataset_version, " +
                    "file_path, action, target_node, policy_rule, decision, reason, token_expires_at, " +
                    "client_ip, created_at FROM dataset_access_audit_event WHERE 1=1",
            "<if test='requestId != null and requestId != \"\"'> AND request_id = #{requestId}</if>",
            "<if test='runId != null and runId != \"\"'> AND run_id = #{runId}</if>",
            "<if test='principal != null and principal != \"\"'> AND principal = #{principal}</if>",
            "ORDER BY event_id DESC LIMIT #{limit}",
            "</script>"})
    List<DatasetAccessAuditEvent> find(@Param("requestId") String requestId,
                                       @Param("runId") String runId,
                                       @Param("principal") String principal,
                                       @Param("limit") int limit);
}
