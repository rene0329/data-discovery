package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface NodeDatasetAccessAuditMapper {
    @Insert("INSERT INTO dataset_access_audit_event " +
            "(request_id, run_id, jti, principal, dataset_id, dataset_version, file_path, " +
            "action, target_node, policy_rule, decision, reason, token_expires_at, client_ip, created_at) " +
            "VALUES (NULL, NULL, #{jti}, #{principal}, #{datasetId}, #{datasetVersion}, #{filePath}, " +
            "#{action}, #{targetNode}, 'NODE_SCOPE_VERIFICATION', #{decision}, #{reason}, " +
            "#{tokenExpiresAt}, NULL, UTC_TIMESTAMP(3))")
    int insert(@Param("jti") String jti,
               @Param("principal") String principal,
               @Param("datasetId") String datasetId,
               @Param("datasetVersion") String datasetVersion,
               @Param("filePath") String filePath,
               @Param("action") String action,
               @Param("targetNode") String targetNode,
               @Param("decision") String decision,
               @Param("reason") String reason,
               @Param("tokenExpiresAt") LocalDateTime tokenExpiresAt);
}
