package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.entity.DataManagement; // 导入 DataManagement 实体
import org.example.entity.NodeManagement; // 导入 NodeManagement 实体

import java.util.List;

@Mapper
public interface DataManagementMapper {
    @Select("select * from data_management where data_name = #{dataName}")
    DataManagement updateOneHeat(DataManagement dataManagement);


    @Select("select * from data_management where data_name = #{dataName}")
    DataManagement getData(DataManagement dataManagement);

    @Select("select * from data_management where data_name = #{dataName}")
    DataManagement findDataByName(String dataName);

//    DataManagement updateStatus(DataManagement dataManagement);

    // 新增方法获取最大nodeId的storage节点 (这个方法不属于DataManagementMapper的职责，但既然您写在这里，暂时保留)
    @Select("SELECT * FROM node_management WHERE type = 'storage' ORDER BY node_id DESC LIMIT 1")
    NodeManagement getMaxNodeIdStorageNode();

    int updateDataServer(DataManagement dataManagement);

    void updateDataHeat(@Param("dataName") String dataName, @Param("newHeat") double newHeat);

    int updateAllDataHeat(@Param("alpha") double alpha,
                          @Param("countWeight") double countWeight,
                          @Param("lambda") double lambda,
                          @Param("threshold") double threshold);

    void save(DataManagement dataManagement); // 这个 save 方法在 XML 中实现，通常用于更新或插入

    /**
     * 根据数据名称获取文件路径。
     * @param dataName 数据名称
     * @return 对应的文件路径
     */
    String getDataServerbyDataName(String dataName); // 注意：这个方法名与我们之前讨论的 file_path 字段语义可能混淆

    List<DataManagement> list(String query);

    List<DataManagement> adminList(String query);

    void updateDataStatus (@Param("data_name") String data_name); // @Param 确保参数名正确

    List<NodeManagement> getCentralityNodes();

    List<DataManagement> getAllDataByHeat(); // 按热度从高到低排序所有数据

    void updateBackupServer(DataManagement dataManagement); // 更新备份服务器字段

    // 更新数据块的 heat 值
    void incrementDataCount(String dataName);

    /**
     * 获取所有数据。
     * @return 所有数据列表
     */
    List<DataManagement> getAllData();

    // --- 为数据内容勘探新增的方法 ---

    /**
     * 获取某个节点上文件最近的修改时间，用于增量更新。
     * @param dataNodeId 节点的ID。
     * @return 该节点上所有文件中的最大 last_modified_time (Unix 毫秒时间戳)。
     */
    Long getMaxLastModifiedTimeForNode(@Param("dataNodeId") Integer dataNodeId);

    /**
     * 根据节点ID和文件路径查找数据记录。
     * 用于判断文件是否已存在或已修改。
     * @param dataNodeId 节点的ID。
     * @param filePath 文件的完整路径。
     * @return 对应的 DataManagement 实体，如果不存在则返回 null。
     */
    DataManagement findByDataNodeIdAndFilePath(@Param("dataNodeId") Integer dataNodeId, @Param("filePath") String filePath);

    /**
     * 插入新的文件数据记录。
     * @param entity 要插入的 DataManagement 实体。
     * @return 影响的行数。
     */
    int insertFile(DataManagement entity);

    /**
     * 更新已存在的文件的物理元数据。
     * 注意：这个方法主要用于更新文件的物理属性（大小、修改时间、哈希等），
     * 应避免修改 data_heat, data_status 等业务属性，除非明确指定。
     * @param entity 包含更新信息的 DataManagement 实体。
     * @return 影响的行数。
     */
    int updateFile(DataManagement entity);

    /**
     * 根据主键删除文件数据记录。
     * @param dataId 要删除记录的主键ID。
     * @return 影响的行数。
     */
    int deleteFile(@Param("dataId") Integer dataId);

    /**
     * 获取某个节点上所有文件的路径列表。
     * 用于检测本次扫描中哪些文件在数据库中存在但已从文件系统删除。
     * @param dataNodeId 节点的ID。
     * @return 该节点上所有文件的路径列表。
     */
    List<String> getAllFilePathsForNode(@Param("dataNodeId") Integer dataNodeId);
}
