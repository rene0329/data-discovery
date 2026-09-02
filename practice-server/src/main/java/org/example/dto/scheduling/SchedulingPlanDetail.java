package org.example.dto.scheduling;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.example.entity.SchedulingAssignment;
import org.example.entity.SchedulingPlan;

import java.util.List;

@Data
@AllArgsConstructor
public class SchedulingPlanDetail {
    private SchedulingPlan plan;
    private List<SchedulingAssignment> assignments;
}
