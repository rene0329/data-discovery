package org.example.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ImpersonationAuditMapper {
    @Insert("INSERT INTO auth_impersonation_audit "
            + "(actor_user_id,actor_username,target_user_id,target_username,action,client_ip) "
            + "VALUES(#{actorUserId},#{actorUsername},#{targetUserId},#{targetUsername},#{action},#{clientIp})")
    int insert(@Param("actorUserId") Long actorUserId,
               @Param("actorUsername") String actorUsername,
               @Param("targetUserId") Long targetUserId,
               @Param("targetUsername") String targetUsername,
               @Param("action") String action,
               @Param("clientIp") String clientIp);
}
