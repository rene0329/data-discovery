package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.entity.DataTransferRateLimitConfiguration;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface DataTransferRateLimitMapper {
    @Select("SELECT config_id, version, enabled, adaptive_enabled, bandwidth_probe_enabled, default_rate, path_rules, " +
            "target_min, target_max, adaptive_safety_factor, measurement_max_age_seconds, " +
            "adaptive_central_node, updated_by, updated_at " +
            "FROM data_transfer_rate_limit_config WHERE config_id=1")
    DataTransferRateLimitConfiguration findCurrent();

    @Update("UPDATE data_transfer_rate_limit_config SET version=#{newVersion}, enabled=#{enabled}, " +
            "adaptive_enabled=#{adaptiveEnabled}, " +
            "bandwidth_probe_enabled=#{bandwidthProbeEnabled}, " +
            "default_rate=#{defaultRate}, path_rules=#{pathRules}, target_min=#{targetMin}, " +
            "target_max=#{targetMax}, adaptive_safety_factor=#{adaptiveSafetyFactor}, " +
            "measurement_max_age_seconds=#{measurementMaxAgeSeconds}, " +
            "adaptive_central_node=#{adaptiveCentralNode}, " +
            "updated_by=#{updatedBy}, updated_at=CURRENT_TIMESTAMP(3) " +
            "WHERE config_id=1 AND version=#{expectedVersion}")
    int update(@Param("expectedVersion") Long expectedVersion,
               @Param("newVersion") Long newVersion,
               @Param("enabled") boolean enabled,
               @Param("adaptiveEnabled") boolean adaptiveEnabled,
               @Param("bandwidthProbeEnabled") boolean bandwidthProbeEnabled,
               @Param("defaultRate") String defaultRate,
               @Param("pathRules") String pathRules,
               @Param("targetMin") BigDecimal targetMin,
               @Param("targetMax") BigDecimal targetMax,
               @Param("adaptiveSafetyFactor") BigDecimal adaptiveSafetyFactor,
               @Param("measurementMaxAgeSeconds") Integer measurementMaxAgeSeconds,
               @Param("adaptiveCentralNode") String adaptiveCentralNode,
               @Param("updatedBy") String updatedBy);

    @Insert("INSERT INTO data_transfer_rate_limit_history " +
            "(version,enabled,adaptive_enabled,bandwidth_probe_enabled,default_rate,path_rules,target_min,target_max," +
            "adaptive_safety_factor,measurement_max_age_seconds,adaptive_central_node,changed_by,changed_at) " +
            "SELECT version,enabled,adaptive_enabled,bandwidth_probe_enabled,default_rate,path_rules,target_min,target_max," +
            "adaptive_safety_factor,measurement_max_age_seconds,adaptive_central_node,updated_by,updated_at " +
            "FROM data_transfer_rate_limit_config WHERE config_id=1")
    int insertCurrentHistory();

    @Select("SELECT history_id, version, enabled, adaptive_enabled, bandwidth_probe_enabled, default_rate, path_rules, " +
            "target_min, target_max, adaptive_safety_factor, measurement_max_age_seconds, adaptive_central_node, " +
            "changed_by AS updated_by, changed_at AS updated_at " +
            "FROM data_transfer_rate_limit_history ORDER BY version DESC LIMIT #{limit}")
    List<DataTransferRateLimitConfiguration> listHistory(@Param("limit") int limit);

    @Select("SELECT history_id, version, enabled, adaptive_enabled, bandwidth_probe_enabled, default_rate, path_rules, " +
            "target_min, target_max, adaptive_safety_factor, measurement_max_age_seconds, adaptive_central_node, " +
            "changed_by AS updated_by, changed_at AS updated_at " +
            "FROM data_transfer_rate_limit_history WHERE version=#{version}")
    DataTransferRateLimitConfiguration findHistoryVersion(@Param("version") Long version);
}
