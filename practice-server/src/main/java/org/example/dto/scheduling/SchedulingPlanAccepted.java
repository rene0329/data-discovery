package org.example.dto.scheduling;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SchedulingPlanAccepted {
    private Long planId;
    private String externalPlanId;
    /** The caller's correlation ID echoed back (SchedulingPlanRequest.taskId), not task_management.task_id. */
    private String taskId;
    private String status;
    /** task_management.task_id of the compute task; null for data-only plans. */
    private Integer internalTaskId;

    public SchedulingPlanAccepted(Long planId, String externalPlanId, String taskId, String status) {
        this(planId, externalPlanId, taskId, status, null);
    }
}
