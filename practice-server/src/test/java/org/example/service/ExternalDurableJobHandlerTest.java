package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.DurableTaskJob;
import org.example.entity.TaskManagement;
import org.example.mapper.*;
import org.example.vo.DataItemResult;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalDurableJobHandlerTest {
    @Test
    void cancelledExternalTaskClosesPlanAndAssignmentsWithoutRecordingARead() {
        SchedulingPlanMapper plans = mock(SchedulingPlanMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        DatasetHeatService heat = mock(DatasetHeatService.class);
        TaskManagement task = TaskManagement.builder().taskId(9).status("取消中").build();
        when(tasks.getTaskByTaskId(9)).thenReturn(task);
        ExternalDurableJobHandler handler = new ExternalDurableJobHandler(plans, tasks,
                mock(DatasetRegistrationMapper.class), mock(DurableTaskJobMapper.class), new ObjectMapper(), heat);
        DurableTaskJob row = row(1L, "CANCELLED");
        handler.finalizePlan(9, Collections.singletonList(row));
        assertEquals("已取消", task.getStatus());
        assertNotNull(task.getFinishedAt());
        verify(plans).updateAssignmentStatus(11L, "CANCELLED", null);
        verify(plans).updatePlanStatus(7L, "CANCELLED", null);
        verifyNoInteractions(heat);
    }

    @Test
    void partialSuccessPreservesSuccessfulReadAndDoesNotFinalizeTwice() throws Exception {
        SchedulingPlanMapper plans = mock(SchedulingPlanMapper.class);
        TaskManagementMapper tasks = mock(TaskManagementMapper.class);
        DatasetHeatService heat = mock(DatasetHeatService.class);
        TaskManagement task = TaskManagement.builder().taskId(9).status("计算中").build();
        when(tasks.getTaskByTaskId(9)).thenReturn(task);
        ObjectMapper json = new ObjectMapper();
        ExternalDurableJobHandler handler = new ExternalDurableJobHandler(plans, tasks,
                mock(DatasetRegistrationMapper.class), mock(DurableTaskJobMapper.class), json, heat);
        DurableTaskJob success = row(1L, "COMPLETED");
        DataItemResult result = new DataItemResult();
        result.setT1Seconds(2.5);
        result.setScheduleT1("dataset: source -> target");
        success.setResultJson(json.writeValueAsString(result));
        DurableTaskJob failed = row(2L, "FAILED");
        failed.setAssignmentId(12L);
        failed.setWaitingReason("执行失败");
        handler.finalizePlan(9, Arrays.asList(success, failed));
        handler.finalizePlan(9, Arrays.asList(success, failed));
        assertEquals("部分完成", task.getStatus());
        assertEquals(2.5, task.getT1());
        verify(heat, times(1)).recordAccess(1L);
        verify(plans).updatePlanStatus(7L, "PARTIAL_COMPLETED", "执行失败");
    }

    private DurableTaskJob row(Long datasetId, String state) {
        DurableTaskJob row = new DurableTaskJob();
        row.setTaskId(9); row.setPlanId(7L); row.setAssignmentId(11L);
        row.setDatasetId(datasetId); row.setState(state); row.setTargetNode("node-a");
        return row;
    }
}
