package org.example.auth;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AuthMapper {
    String USER_SELECT = "SELECT u.user_id, u.username, u.password_hash, u.display_name, "
            + "u.domain_id, u.enabled, u.token_version, u.created_at, u.updated_at, "
            + "d.domain_code, d.name domain_name, d.enabled domain_enabled "
            + "FROM app_user u LEFT JOIN collaboration_domain d ON d.domain_id=u.domain_id ";

    @Select(USER_SELECT + "WHERE u.username=#{username}")
    AuthUserRecord findUserByUsername(String username);

    @Select(USER_SELECT + "WHERE u.user_id=#{userId}")
    AuthUserRecord findUserById(Long userId);

    @Select(USER_SELECT + "ORDER BY u.user_id")
    List<AuthUserRecord> listUsers();

    @Select("SELECT r.role_code FROM app_role r JOIN app_user_role ur ON ur.role_id=r.role_id "
            + "WHERE ur.user_id=#{userId} ORDER BY r.role_code")
    List<String> listRoleCodes(Long userId);

    String DOMAIN_SELECT = "SELECT domain_id id, domain_code code, name, site_code, description, enabled, "
            + "created_at, updated_at FROM collaboration_domain ";

    @Select(DOMAIN_SELECT + "ORDER BY domain_id")
    List<CollaborationDomain> listDomains();

    @Select(DOMAIN_SELECT + "WHERE domain_id=#{id}")
    CollaborationDomain findDomainById(Long id);

    @Select(DOMAIN_SELECT + "WHERE domain_code=#{code}")
    CollaborationDomain findDomainByCode(String code);

    @Select(DOMAIN_SELECT + "WHERE site_code=#{siteCode}")
    CollaborationDomain findDomainBySiteCode(String siteCode);

    @Insert("INSERT INTO collaboration_domain(domain_code,name,site_code,description,enabled) "
            + "VALUES(#{code},#{name},#{siteCode},#{description},#{enabled})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDomain(CollaborationDomain domain);

    @Update("UPDATE collaboration_domain SET name=#{name}, site_code=#{siteCode}, description=#{description}, "
            + "enabled=#{enabled} WHERE domain_id=#{id}")
    int updateDomain(CollaborationDomain domain);

    @Insert("INSERT INTO app_user(username,password_hash,display_name,domain_id,enabled,token_version) "
            + "VALUES(#{username},#{passwordHash},#{displayName},#{domainId},#{enabled},#{tokenVersion})")
    @Options(useGeneratedKeys = true, keyProperty = "userId")
    int insertUser(AuthUserRecord user);

    @Update("UPDATE app_user SET display_name=#{displayName}, domain_id=#{domainId}, enabled=#{enabled}, "
            + "token_version=token_version+1 WHERE user_id=#{userId}")
    int updateUser(AuthUserRecord user);

    @Update("UPDATE app_user SET password_hash=#{passwordHash}, token_version=token_version+1 "
            + "WHERE user_id=#{userId}")
    int resetPassword(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    @Delete("DELETE FROM app_user_role WHERE user_id=#{userId}")
    int deleteUserRoles(Long userId);

    @Insert("INSERT INTO app_user_role(user_id,role_id) "
            + "SELECT #{userId}, role_id FROM app_role WHERE role_code=#{roleCode}")
    int insertUserRole(@Param("userId") Long userId, @Param("roleCode") String roleCode);
}
