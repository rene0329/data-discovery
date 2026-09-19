package org.example.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** Shared replay ledger for single-use Agent access tokens. */
@Mapper
public interface NodeDatasetAccessTokenConsumptionMapper {
    @Insert("INSERT INTO dataset_access_token_consumption " +
            "(jti,target_node,action,consumed_at,expires_at) " +
            "VALUES (#{jti},#{targetNode},#{action},UTC_TIMESTAMP(3),#{expiresAt})")
    int consume(@Param("jti") String jti,
                @Param("targetNode") String targetNode,
                @Param("action") String action,
                @Param("expiresAt") LocalDateTime expiresAt);

    @Delete("DELETE FROM dataset_access_token_consumption WHERE expires_at <= UTC_TIMESTAMP(3)")
    int deleteExpired();
}
