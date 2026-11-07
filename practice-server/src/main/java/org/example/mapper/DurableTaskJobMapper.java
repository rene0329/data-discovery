package org.example.mapper;
import org.apache.ibatis.annotations.*;
import org.example.entity.DurableTaskJob;
import java.util.List;
@Mapper
public interface DurableTaskJobMapper {
    @Insert("INSERT INTO task_compute_job (job_key,task_id,dataset_id,execution_mode,plan_id,assignment_id,runtime_image_id,overrides_json,replica_id,target_node,cluster_id,cpu,memory_gi,gpu,ephemeral_storage_gi,state,waiting_reason,job_name,job_uid,migration_id,attempt,external_prepared,result_json,queued_at,submitted_at,bound_at,next_attempt_at,finished_at) VALUES (#{jobKey},#{taskId},#{datasetId},#{executionMode},#{planId},#{assignmentId},#{runtimeImageId},#{overridesJson},#{replicaId},#{targetNode},#{clusterId},#{cpu},#{memoryGi},#{gpu},#{ephemeralStorageGi},#{state},#{waitingReason},#{jobName},#{jobUid},#{migrationId},#{attempt},#{externalPrepared},#{resultJson},#{queuedAt},#{submittedAt},#{boundAt},#{nextAttemptAt},#{finishedAt}) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")
    @Options(useGeneratedKeys=true,keyProperty="id") int insert(DurableTaskJob job);
    @Update("UPDATE task_compute_job SET plan_id=#{planId},assignment_id=#{assignmentId},runtime_image_id=#{runtimeImageId},overrides_json=#{overridesJson},replica_id=#{replicaId},target_node=#{targetNode},cluster_id=#{clusterId},comparison_node=#{comparisonNode},cpu=#{cpu},memory_gi=#{memoryGi},gpu=#{gpu},ephemeral_storage_gi=#{ephemeralStorageGi},state=#{state},waiting_reason=#{waitingReason},job_name=#{jobName},job_uid=#{jobUid},migration_id=#{migrationId},attempt=#{attempt},result_json=#{resultJson},submitted_at=#{submittedAt},bound_at=#{boundAt},next_attempt_at=#{nextAttemptAt},finished_at=#{finishedAt},cleaned_at=#{cleanedAt},credential_refreshed_at=#{credentialRefreshedAt} WHERE id=#{id}") int update(DurableTaskJob job);
    @Select("SELECT * FROM task_compute_job WHERE task_id=#{taskId} ORDER BY id") List<DurableTaskJob> listByTask(Integer taskId);
    @Select("SELECT * FROM task_compute_job WHERE state NOT IN ('COMPLETED','FAILED','CANCELLED') ORDER BY queued_at,id") List<DurableTaskJob> listActive();
    @Update("UPDATE task_compute_job SET state='FAILING',waiting_reason=#{reason} WHERE id=#{id} AND state NOT IN ('CANCELLING','CANCELLED','COMPLETED','FAILED')")
    int markPreparationFailure(@Param("id") Long id,@Param("reason") String reason);
    @Update("UPDATE task_compute_job SET external_prepared=1 WHERE id=#{id}") int markExternalPrepared(Long id);
    @Select("SELECT * FROM task_compute_job WHERE state IN ('COMPLETED','FAILED','CANCELLED') AND cleaned_at IS NULL AND job_name IS NOT NULL ORDER BY id LIMIT 100") List<DurableTaskJob> listCleanup();
    @Select("SELECT * FROM task_compute_job WHERE id=#{id}") DurableTaskJob find(Long id);
    @Select("SELECT COUNT(*) FROM task_compute_job WHERE task_id=#{taskId}") int countByTask(Integer taskId);
    @Select("SELECT task_id FROM task_compute_job j WHERE NOT EXISTS (SELECT 1 FROM task_compute_job a WHERE a.task_id=j.task_id AND a.state NOT IN ('COMPLETED','FAILED','CANCELLED')) AND EXISTS (SELECT 1 FROM task_management t WHERE t.task_id=j.task_id AND t.status NOT IN ('已完成','部分完成','执行失败','已取消')) GROUP BY task_id") List<Integer> tasksToFinalize();
}
