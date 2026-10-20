package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.entity.TaskManagement;
import java.util.List;

@Mapper
public interface TaskRecoveryMapper {
    List<TaskManagement> listInterruptedTasks();
    int failTask(@Param("taskId") Integer taskId);
    int failMigrations(@Param("taskId") Integer taskId, @Param("reason") String reason);
    int failAssignments(@Param("taskId") Integer taskId, @Param("reason") String reason);
    int failPlans(@Param("taskId") Integer taskId, @Param("reason") String reason);
}
