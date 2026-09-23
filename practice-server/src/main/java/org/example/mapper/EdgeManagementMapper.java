package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.entity.EdgeManagement;
import java.util.List;

/** Probe measurements per node pair; which pairs form the topology is decided by NetworkTopologyService. */
@Mapper
public interface EdgeManagementMapper {
    List<EdgeManagement> selectAllMetrics();

    EdgeManagement findBySourceAndTargetNode(@Param("sourceId") Integer sourceId,
                                             @Param("targetId") Integer targetId);

    int upsertMetric(EdgeManagement entity);

    @Update("UPDATE node_link_metric SET status = 'inactive' WHERE source_id = #{nodeId} OR target_id = #{nodeId}")
    int deactivateByNodeId(@Param("nodeId") Integer nodeId);
}
