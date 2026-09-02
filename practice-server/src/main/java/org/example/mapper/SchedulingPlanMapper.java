package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.entity.SchedulingAssignment;
import org.example.entity.SchedulingPlan;

import java.util.List;

@Mapper
public interface SchedulingPlanMapper {
    int insertPlan(SchedulingPlan plan);
    int insertAssignment(SchedulingAssignment assignment);
    SchedulingPlan findByExternalPlanId(String externalPlanId);
    SchedulingPlan findById(Long planId);
    List<SchedulingAssignment> listAssignments(Long planId);
    int updatePlanStatus(@Param("planId") Long planId,
                         @Param("status") String status,
                         @Param("errorMessage") String errorMessage);
    int updateAssignmentStatus(@Param("assignmentId") Long assignmentId,
                               @Param("status") String status,
                               @Param("errorMessage") String errorMessage);
}
