package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.entity.EdgeManagement;
import java.util.List;

/** Reads approved logical links; probe updates cannot change their endpoints. */
@Mapper
public interface EdgeManagementMapper {
    List<EdgeManagement> links();
    List<EdgeManagement> selectAllEdges();
    EdgeManagement findBySourceAndTargetNode(@Param("sourceId") Integer sourceId,
                                           @Param("targetId") Integer targetId);

    @Update("UPDATE logical_topology_edge SET bandwidth = #{bandwidth}, latency = #{latency}, " +
            "status = #{status}, measurement_time = UTC_TIMESTAMP(3) WHERE edge_id = #{edgeId}")
    int updateEdge(EdgeManagement entity);

    @Update("UPDATE logical_topology_edge e JOIN node_management n " +
            "ON n.node_name = e.source_node_name OR n.node_name = e.target_node_name " +
            "SET e.status = 'inactive' WHERE n.node_id = #{nodeId}")
    int deactivateByNodeId(@Param("nodeId") Integer nodeId);
}
