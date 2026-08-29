package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RegistrationAuditMapper {
    @Insert("INSERT INTO registration_audit_log " +
            "(resource_type, resource_id, action, actor, request_id, detail_json, created_at) " +
            "VALUES (#{resourceType}, #{resourceId}, #{action}, #{actor}, #{requestId}, " +
            "#{detailJson}, UTC_TIMESTAMP(3))")
    int insert(@Param("resourceType") String resourceType,
               @Param("resourceId") String resourceId,
               @Param("action") String action,
               @Param("actor") String actor,
               @Param("requestId") String requestId,
               @Param("detailJson") String detailJson);

    @Select("SELECT resource_id FROM registration_audit_log " +
            "WHERE resource_type = #{resourceType} AND action = #{action} AND request_id = #{requestId} " +
            "ORDER BY audit_id DESC LIMIT 1")
    String findResourceIdByRequest(@Param("resourceType") String resourceType,
                                   @Param("action") String action,
                                   @Param("requestId") String requestId);
}
