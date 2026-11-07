package org.example.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Administrative deletion must never erase the identity of a live computation. */
@Mapper
public interface TaskLifecycleAdminMapper {
    @Select("SELECT COUNT(*) FROM task_compute_job WHERE task_id=#{taskId}")
    int countJobsForTask(@Param("taskId") Integer taskId);
    @Select("SELECT COUNT(*) FROM task_compute_job WHERE state NOT IN ('COMPLETED','FAILED','CANCELLED') OR (job_name IS NOT NULL AND cleaned_at IS NULL)")
    int countActiveJobs();

    @Select("SELECT COUNT(*) FROM task_compute_job WHERE task_id=#{taskId} AND (state NOT IN ('COMPLETED','FAILED','CANCELLED') OR (job_name IS NOT NULL AND cleaned_at IS NULL))")
    int countActiveJobsForTask(@Param("taskId") Integer taskId);

    @Delete("DELETE FROM task_compute_job WHERE task_id=#{taskId} AND state IN ('COMPLETED','FAILED','CANCELLED') AND (job_name IS NULL OR cleaned_at IS NOT NULL)")
    int deleteTerminalJobs(@Param("taskId") Integer taskId);

}
