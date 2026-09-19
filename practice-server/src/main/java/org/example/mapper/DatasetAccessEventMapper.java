package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.access.DatasetAccessEvent;
import org.example.access.DatasetConsumerStat;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DatasetAccessEventMapper {
    @Insert("INSERT INTO dataset_access_event (request_id,run_id,task_id,dataset_id,dataset_version," +
            "consumer_node_id,source_replica_id,source_node_id,route_reason,expected_bytes,bytes_read," +
            "cache_hit_bytes,started_at,success) VALUES (#{requestId},#{runId},#{taskId},#{datasetId}," +
            "#{datasetVersion},#{consumerNodeId},#{sourceReplicaId},#{sourceNodeId},#{routeReason}," +
            "#{expectedBytes},0,0,#{startedAt},0)")
    @Options(useGeneratedKeys = true, keyProperty = "eventId")
    int insertStarted(DatasetAccessEvent event);

    @Update("UPDATE dataset_access_event SET cache_layer=#{cacheLayer},bytes_read=#{bytesRead}," +
            "cache_hit_bytes=#{cacheHitBytes},checksum=#{checksum},first_byte_at=#{firstByteAt}," +
            "completed_at=#{completedAt},duration_ms=#{durationMs},success=1,failure_reason=NULL " +
            "WHERE event_id=#{eventId} AND success=0")
    int complete(DatasetAccessEvent event);

    @Update("UPDATE dataset_access_event SET completed_at=#{completedAt},duration_ms=#{durationMs}," +
            "success=0,failure_reason=#{failureReason} WHERE event_id=#{eventId}")
    int fail(DatasetAccessEvent event);

    @Select("SELECT * FROM dataset_access_event WHERE request_id=#{requestId}")
    DatasetAccessEvent findByRequestId(String requestId);

    @Select({"<script>SELECT * FROM dataset_access_event WHERE 1=1",
            "<if test='datasetId != null'> AND dataset_id=#{datasetId}</if>",
            "<if test='runId != null and runId != &quot;&quot;'> AND run_id=#{runId}</if>",
            " ORDER BY event_id DESC LIMIT #{limit}</script>"})
    List<DatasetAccessEvent> list(@Param("datasetId") Long datasetId,
                                  @Param("runId") String runId,
                                  @Param("limit") int limit);

    @Select("SELECT consumer_node_id,COUNT(*) access_count,SUM(bytes_read) bytes_read," +
            "MAX(completed_at) last_access_at FROM dataset_access_event " +
            "WHERE dataset_id=#{datasetId} AND success=1 AND completed_at >= #{since} " +
            "GROUP BY consumer_node_id ORDER BY access_count DESC,last_access_at DESC,consumer_node_id")
    List<DatasetConsumerStat> recentConsumers(@Param("datasetId") Long datasetId,
                                               @Param("since") LocalDateTime since);
}
