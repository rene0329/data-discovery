package org.example.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.entity.ApiIdempotencyRecord;

@Mapper
public interface ApiIdempotencyMapper {

    @Insert("INSERT IGNORE INTO api_idempotency_record " +
            "(idempotency_key, resource_type, action, request_hash, execution_status, created_at) " +
            "VALUES (#{key}, #{resourceType}, #{action}, #{requestHash}, 'PROCESSING', UTC_TIMESTAMP(3))")
    int reserve(@Param("key") String key,
                @Param("resourceType") String resourceType,
                @Param("action") String action,
                @Param("requestHash") String requestHash);

    @Select("SELECT id, idempotency_key, resource_type, action, request_hash, resource_id, " +
            "response_json, execution_status, created_at, completed_at " +
            "FROM api_idempotency_record WHERE idempotency_key = #{key} " +
            "AND resource_type = #{resourceType} AND action = #{action} LIMIT 1")
    ApiIdempotencyRecord find(@Param("key") String key,
                              @Param("resourceType") String resourceType,
                              @Param("action") String action);

    @Update("UPDATE api_idempotency_record SET created_at = UTC_TIMESTAMP(3) " +
            "WHERE idempotency_key = #{key} AND resource_type = #{resourceType} " +
            "AND action = #{action} AND request_hash = #{requestHash} " +
            "AND execution_status = 'PROCESSING' " +
            "AND created_at < DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 2 HOUR)")
    int takeOverStale(@Param("key") String key,
                      @Param("resourceType") String resourceType,
                      @Param("action") String action,
                      @Param("requestHash") String requestHash);

    @Update("UPDATE api_idempotency_record SET resource_id = #{resourceId}, " +
            "response_json = #{responseJson}, execution_status = 'COMPLETED', " +
            "completed_at = UTC_TIMESTAMP(3) WHERE idempotency_key = #{key} " +
            "AND resource_type = #{resourceType} AND action = #{action} " +
            "AND execution_status = 'PROCESSING'")
    int complete(@Param("key") String key,
                 @Param("resourceType") String resourceType,
                 @Param("action") String action,
                 @Param("resourceId") String resourceId,
                 @Param("responseJson") String responseJson);

    @Delete("DELETE FROM api_idempotency_record WHERE idempotency_key = #{key} " +
            "AND resource_type = #{resourceType} AND action = #{action} " +
            "AND execution_status = 'PROCESSING'")
    int release(@Param("key") String key,
                @Param("resourceType") String resourceType,
                @Param("action") String action);
}
