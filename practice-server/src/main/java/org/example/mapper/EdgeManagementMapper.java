// src/main/java/org/example/mapper/EdgeManagementMapper.java
package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.example.entity.EdgeManagement;

import java.util.List;

@Mapper
public interface EdgeManagementMapper {

    // (您原有的 links() 方法，如果仍需要，可以保留)
    // 但通常会使用更通用的 selectAllEdges 或 getLiveLinks 等
//    @Select("SELECT edge_id AS edgeId, source_id AS sourceId, target_id AS targetId, " +
//            "bandwidth, latency, status FROM edge_management")
    List<EdgeManagement> links(); // 确保手动映射，或者依赖MyBatis驼峰自动映射

    /**
     * 插入新的网络链路测量数据。
     * 修正 SQL 字段名以匹配数据库。
     * @param entity 要插入的 EdgeManagement 实体。
     * @return 影响的行数。
     */
    @Insert("INSERT INTO edge_management (source_id, target_id, bandwidth, latency, status) " +
            "VALUES (#{sourceId}, #{targetId}, #{bandwidth}, #{latency}, #{status})")
    int insertEdge(EdgeManagement entity);

    /**
     * 更新已存在的网络链路测量数据。
     * 修正 SQL 字段名以匹配数据库。
     * @param entity 包含更新信息的 EdgeManagement 实体。必须包含 edgeId。
     * @return 影响的行数。
     */
    @Update("UPDATE edge_management " +
            "SET bandwidth = #{bandwidth}, " +
            "    latency = #{latency}, " +
            "    status = #{status} " +
            "WHERE edge_id = #{edgeId}") // 修正 WHERE 条件中的 edge_id
    int updateEdge(EdgeManagement entity);

    /**
     * 根据源节点ID和目标节点ID查找现有的网络链路记录。
     * 修正 SQL 字段名以匹配数据库，并使用 AS 别名确保映射到 Entity 属性。
     * @param sourceId 源节点ID。
     * @param targetId 目标节点ID。
     * @return 对应的 EdgeManagement 实体，如果不存在则返回 null。
     */
    @Select("SELECT edge_id AS edgeId, source_id AS sourceId, target_id AS targetId, " +
            "bandwidth, latency, status FROM edge_management " +
            "WHERE (source_id = #{sourceId} AND target_id = #{targetId}) " +
            "   OR (source_id = #{targetId} AND target_id = #{sourceId}) " +
            "LIMIT 1")
    EdgeManagement findBySourceAndTargetNode(@Param("sourceId") Integer sourceId, @Param("targetId") Integer targetId);

    /**
     * 获取所有网络链路信息。
     * 使用 AS 别名确保映射到 Entity 属性。
     * @return 所有 EdgeManagement 实体列表。
     */
    @Select("SELECT edge_id AS edgeId, source_id AS sourceId, target_id AS targetId, " +
            "bandwidth, latency, status FROM edge_management")
    List<EdgeManagement> selectAllEdges();

    @Update("UPDATE edge_management SET status = 'inactive' " +
            "WHERE source_id = #{nodeId} OR target_id = #{nodeId}")
    int deactivateByNodeId(@Param("nodeId") Integer nodeId);
}
