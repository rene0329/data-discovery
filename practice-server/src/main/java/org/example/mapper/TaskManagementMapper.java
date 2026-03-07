package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.example.entity.TaskManagement;

import java.util.List;

@Mapper
public interface TaskManagementMapper {
    void submitData(TaskManagement taskManagement);

    List<TaskManagement> list(String query);

    // 根据 dataName 获取 data_server（node1）
    String getDataServerByDataName(String dataName);

    // 获取 node_id 对应的 node_name
    String getNodeNameById(Integer nodeId);

    // 获取 node_name 对应的 node_id
    Integer getNodeIdByName(String nodeName);

    // 获取 task_id 对应的 taskmanagement对象
    TaskManagement getTaskByTaskId(Integer taskId);

    String getBandWidthBySourceId(@Param("sourceId") Integer sourceId, @Param("targetId") Integer targetId);
    // 根据 dataName 获取 data_size
    Double getDataSizeByDataName(String dataName);

    // 计算传输到最近 compute 节点的时间 t1
    Double calculateT1(Integer sourceId, Integer targetId, Double dataSize);

    // 计算传输到中心节点 (node_id = 1) 的时间 t2
    Double calculateT2(Integer sourceId, Double dataSize);

    // 获取离 node1 最近的 compute 节点
    Integer getClosestComputeNode(@Param("nodeId") Integer nodeId, @Param("dataSize") Double dataSize);

    Integer updateTask(TaskManagement taskManagement);


    // 删除所有任务
    void deleteAllTasks();

    // 删除单个任务
    void deleteTaskById(@Param("taskId") Integer taskId);

    // 重置自增 ID
    @Update("ALTER TABLE task_management AUTO_INCREMENT = 1")
    void resetAutoIncrement();

    // 调度展示：返回有 schedule 内容的任务
    List<TaskManagement> listWithSchedule(String query);

    // 性能分析：返回有 T1/T2/rating 数据的任务
    List<TaskManagement> listWithAnalysis(String query);

}
