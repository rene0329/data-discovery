package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.entity.NodeManagement;

import java.util.List;

@Mapper
public interface NodeManagementMapper {

    // --- 您已有的方法 (全部保留) ---

    List<NodeManagement> networkConfiguration(String query);

    @Select("select * from node_management where deleted_at IS NULL")
    List<NodeManagement> networkConstruction();

    @Select("select * from node_management where node_name = #{nodeName}")
    NodeManagement getNodeDataByNodeName(NodeManagement nodeManagement);

    @Select("select * from node_management where node_id = #{nodeId} and deleted_at IS NULL")
    NodeManagement getNodeById(Integer nodeId);

    @Select("select * from node_management where node_name = #{nodeName} and deleted_at IS NULL")
    NodeManagement getNodeByName(String nodeName);

    @Select("select * from node_management where node_name = #{nodeName} and cluster = #{cluster} and deleted_at IS NULL")
    NodeManagement getByNameAndCluster(@Param("nodeName") String nodeName, @Param("cluster") String cluster);

    NodeManagement getByClusterAndK8sUid(@Param("cluster") String cluster,
                                         @Param("k8sUid") String k8sUid);

    List<NodeManagement> listRegisteredNodes(@Param("query") String query,
                                             @Param("status") String status,
                                             @Param("enabled") Boolean enabled);

    List<NodeManagement> listRegisteredNodesByCluster(@Param("cluster") String cluster);

    /**
     * 查询所有具备计算能力的节点（纯计算节点 + 计算存储双角色节点），供 K8sJobFactory 调度使用。
     * 不依赖 K8s label，以 DB 记录为准，支持双角色节点。
     */
    @Select("SELECT * FROM node_management WHERE type IN ('compute', 'compute-storage') " +
            "AND registration_status = 'ACTIVE' AND enabled = 1 AND deleted_at IS NULL")
    List<NodeManagement> getComputeCapableNodes();

    // 获取所有节点
        @Select("SELECT node_id AS nodeId, node_name AS nodeName, external_ip AS externalIp, internal_ip AS internalIp, type, cluster, " +
            "current_cpu AS currentCpu, max_cpu AS maxCpu, current_memory AS currentMemory, max_memory AS maxMemory, " +
            "num_dataset AS numDataset, last_update_time AS lastUpdateTime " +
            "FROM node_management WHERE deleted_at IS NULL")
    List<NodeManagement> selectAllNodes();
    String getNodeIpByDataServer(String dataServer);
    String getNodeIpById(Integer nodeId);
    void updateSystemInfo(Integer nodeId, Double cpu, Double memUsage, Double diskUsage);
    Integer getNodeIdByIp(String nodeIp);
    void clearAllDataServers();
    List<Integer> getAvailableComputeNodes(Integer storageNodeId);
    List<Integer> getAllComputeNodes();
    double getCurrentMemoryById(Integer nodeId);
    double getMaxMemoryById(Integer nodeId);
    int getDataCountByNode(String nodeName);
    void updateNodeMemoryUsage(@Param("nodeId") Integer nodeId, @Param("usedMemory") Double usedMemory);
    String getNodeNameById(Integer nodeId);
    Integer edgeExists(@Param("node1Id") int node1Id, @Param("nodeId") int nodeId);

    @Update("UPDATE node_management SET current_memory = current_memory - #{memoryCost} WHERE node_id = #{nodeId}")
    void releaseNodeMemoryUsage(@Param("nodeId") int nodeId, @Param("memoryCost") double memoryCost);

    // --- 为NodeSyncService新增的方法 ---

    /**
     * [新增] 插入一个由K8s同步过来的新节点。
     * @param node 从K8sNodeMapper转换来的实体，只包含K8s相关信息。
     * @return 影响的行数
     */
    int insertNode(NodeManagement node);

    int insertRegisteredNode(NodeManagement node);

    int updateNodeObservation(NodeManagement node);

    int updateObservedPublicIp(@Param("cluster") String cluster,
                               @Param("k8sUid") String k8sUid,
                               @Param("nodeName") String nodeName,
                               @Param("publicIp") String publicIp);

    int attachK8sIdentity(@Param("nodeId") Integer nodeId,
                          @Param("cluster") String cluster,
                          @Param("k8sUid") String k8sUid);

    int updateRegistrationMetadata(NodeManagement node);

    int updateRegistrationState(@Param("nodeId") Integer nodeId,
                                @Param("status") String status,
                                @Param("enabled") boolean enabled,
                                @Param("verified") boolean verified);

    int markOfflineByClusterAndK8sUid(@Param("cluster") String cluster,
                                      @Param("k8sUid") String k8sUid,
                                      @Param("threshold") int threshold);

    int softDeleteRegisteredNode(Integer nodeId);

    int countDatasetReplicasByNode(Integer nodeId);

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM migration_task " +
            "WHERE (source_node_id = #{nodeId} OR target_node_id = #{nodeId}) " +
            "AND status NOT IN ('COMPLETED', 'FAILED')")
    int countActiveMigrationTasksByNode(Integer nodeId);

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM task_management " +
            "WHERE schedule LIKE CONCAT('%', #{nodeName}, '%') " +
            "AND status NOT IN ('已完成', '执行失败', '部分完成', 'COMPLETED', 'FAILED')")
    int countActiveTasksByNodeName(String nodeName);

    /**
     * [新增] 更新一个已存在的节点，只更新来自K8s的信息。
     * 注意：这个更新操作不会修改 current_cpu, current_memory 等运行时指标。
     * @param node 从K8sNodeMapper转换来的实体。
     * @return 影响的行数
     */
    int updateNodeFromK8s(NodeManagement node);

    /**
     * [新增] 当K8s中节点被删除时，从数据库中删除对应的记录。
     * @param nodeName 要删除的节点名称。
     * @return 影响的行数
     */
    int deleteByName(String nodeName);
}
