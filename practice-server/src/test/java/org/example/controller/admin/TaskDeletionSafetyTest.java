package org.example.controller.admin;

import org.example.entity.TaskManagement;
import org.example.mapper.*;
import org.example.service.NetworkTopologyService;
import org.example.service.NodeAvailabilityService;
import org.example.service.PublicIpLocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskDeletionSafetyTest {
    private TaskManagementMapper tasks;
    private MigrationTaskMapper migrations;
    private TaskLifecycleAdminMapper jobs;
    private CommonController controller;

    @BeforeEach
    void setup() {
        tasks = mock(TaskManagementMapper.class);
        migrations = mock(MigrationTaskMapper.class);
        jobs = mock(TaskLifecycleAdminMapper.class);
        controller = new CommonController(mock(DataManagementMapper.class), mock(NodeManagementMapper.class),
                tasks, migrations, mock(NetworkTopologyService.class), mock(RestTemplate.class),
                new NodeAvailabilityService(300), mock(PublicIpLocationService.class));
        ReflectionTestUtils.setField(controller, "taskLifecycleAdminMapper", jobs);
    }

    @Test
    void waitingTaskCannotBeDeleted() {
        TaskManagement task = TaskManagement.builder().taskId(1).status("等待资源").build();
        when(tasks.getTaskByTaskId(1)).thenReturn(task);
        assertEquals(409, controller.deleteTask(task).getStatusCodeValue());
        verify(tasks, never()).deleteTaskById(any());
        verifyNoInteractions(migrations);
    }

    @Test
    void terminalTaskWithPodsStillBeingReleasedCannotBeDeleted() {
        TaskManagement task = TaskManagement.builder().taskId(1).status("已取消").build();
        when(tasks.getTaskByTaskId(1)).thenReturn(task);
        when(jobs.countActiveJobsForTask(1)).thenReturn(1);
        assertEquals(409, controller.deleteTask(task).getStatusCodeValue());
        verify(tasks, never()).deleteTaskById(any());
    }

    @Test
    void bulkClearPreservesTaskSubmittedAfterInitialCheckAndNeverReusesIds() {
        when(tasks.list("")).thenReturn(Arrays.asList(
                TaskManagement.builder().taskId(1).status("已完成").build(),
                TaskManagement.builder().taskId(2).status("等待资源").build()));
        assertEquals(200, controller.clearTasks().getStatusCodeValue());
        verify(tasks).deleteTaskById(1);
        verify(tasks, never()).deleteTaskById(2);
        verify(tasks, never()).deleteAllTasks();
        verify(tasks, never()).resetAutoIncrement();
        verify(migrations, never()).deleteAll();
        verify(migrations).deleteByTaskId(1);
    }
}
