package org.example.security.access;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.entity.RegisteredDataset;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Persistence for self-service dataset usage grants. "Active" is always
 * evaluated against a caller-supplied UTC {@code now}, so the response's
 * serverTime and the grant filter use the same instant.
 */
@Mapper
public interface DatasetAccessGrantMapper {
    String GRANT_COLUMNS = "grant_id, user_id, dataset_id, reason, created_at, expires_at";

    @Insert("INSERT INTO dataset_access_grant (user_id, dataset_id, reason, created_at, expires_at) " +
            "VALUES (#{userId}, #{datasetId}, #{reason}, #{createdAt}, #{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "grantId")
    int insert(DatasetAccessGrant grant);

    @Select("SELECT " + GRANT_COLUMNS + " FROM dataset_access_grant " +
            "WHERE user_id = #{userId} AND expires_at > #{now} " +
            "ORDER BY expires_at DESC, grant_id DESC")
    List<DatasetAccessGrant> findActiveByUser(@Param("userId") Long userId,
                                              @Param("now") LocalDateTime now);

    @Select({"<script>",
            "SELECT " + GRANT_COLUMNS + " FROM dataset_access_grant",
            "WHERE user_id = #{userId} AND expires_at &gt; #{now} AND dataset_id IN",
            "<foreach collection='datasetIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "ORDER BY expires_at DESC, grant_id DESC",
            "</script>"})
    List<DatasetAccessGrant> findActiveByUserAndDatasets(@Param("userId") Long userId,
                                                         @Param("datasetIds") Collection<Long> datasetIds,
                                                         @Param("now") LocalDateTime now);

    /** 访问申请日志: the newest grants first, each with its applicant and dataset. */
    @Select("SELECT g.grant_id, g.user_id, u.username, u.display_name, d.name AS domain_name, " +
            "g.dataset_id, rd.name AS dataset_name, rd.dataset_code, rd.dataset_version, " +
            "g.reason, g.created_at, g.expires_at " +
            "FROM dataset_access_grant g " +
            "LEFT JOIN app_user u ON u.user_id = g.user_id " +
            "LEFT JOIN collaboration_domain d ON d.domain_id = u.domain_id " +
            "LEFT JOIN registered_dataset rd ON rd.dataset_id = g.dataset_id " +
            "ORDER BY g.grant_id DESC LIMIT #{limit}")
    List<DatasetAccessGrantLogRow> findGrantLog(@Param("limit") int limit);

    /** Serializes concurrent grant requests of one user so "already active" stays exact. */
    @Select("SELECT user_id FROM app_user WHERE user_id = #{userId} FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);

    /** Ownership facts needed by the usage policy; soft-deleted datasets are not returned. */
    @Select({"<script>",
            "SELECT dataset_id, dataset_version, owner_domain_id FROM registered_dataset",
            "WHERE deleted_at IS NULL AND dataset_id IN",
            "<foreach collection='datasetIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    List<RegisteredDataset> findDatasetOwnership(@Param("datasetIds") Collection<Long> datasetIds);
}
