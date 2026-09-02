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
    private String taskId;
    private String status;
}
