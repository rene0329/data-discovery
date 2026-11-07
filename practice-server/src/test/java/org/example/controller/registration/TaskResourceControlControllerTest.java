package org.example.controller.registration;

import org.example.dto.registration.TaskExecutionDetail;
import org.example.entity.DurableTaskJob;
import org.example.entity.TaskManagement;
import org.example.service.ApiIdempotencyService;
import org.example.service.DurableTaskSchedulingService;
import org.example.service.TaskV1Service;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskResourceControlControllerTest {
    @Test
    void waitingJobsAreVisibleWithoutInternalPayloads() throws Exception {
        TaskV1Service service = mock(TaskV1Service.class);
        DurableTaskSchedulingService scheduling = mock(DurableTaskSchedulingService.class);
        TaskV1Controller controller = new TaskV1Controller(service, mock(ApiIdempotencyService.class));
        ReflectionTestUtils.setField(controller, "scheduling", scheduling);
        DurableTaskJob job = new DurableTaskJob();
        job.setState("WAITING");
        job.setWaitingReason("所有候选节点资源不足");
        job.setOverridesJson("internal-overrides");
        job.setResultJson("internal-result");
        when(service.getExecution(12)).thenReturn(new TaskExecutionDetail(new TaskManagement(), Collections.emptyList()));
        when(scheduling.listJobs(12)).thenReturn(Collections.singletonList(job));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v1/tasks/12/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].state").value("WAITING"))
                .andExpect(jsonPath("$.data[0].waitingReason").value("所有候选节点资源不足"))
                .andExpect(jsonPath("$.data[0].overridesJson").doesNotExist())
                .andExpect(jsonPath("$.data[0].resultJson").doesNotExist());
    }

    @Test
    void cancellationReturnsAcceptedAndCurrentState() throws Exception {
        TaskV1Service service = mock(TaskV1Service.class);
        DurableTaskSchedulingService scheduling = mock(DurableTaskSchedulingService.class);
        TaskV1Controller controller = new TaskV1Controller(service, mock(ApiIdempotencyService.class));
        ReflectionTestUtils.setField(controller, "scheduling", scheduling);
        when(scheduling.listJobs(12)).thenReturn(Collections.emptyList());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/tasks/12/cancel")).andExpect(status().isAccepted());
        verify(service).getExecution(12);
        verify(scheduling).cancel(12);
    }
}
