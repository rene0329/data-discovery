package org.example.controller.registration;

import org.example.dto.scheduling.SchedulingPageResult;
import org.example.dto.scheduling.SchedulingPlanDetail;
import org.example.dto.scheduling.SchedulingPlanAccepted;
import org.example.entity.SchedulingAssignment;
import org.example.entity.SchedulingPlan;
import org.example.exception.RegistrationException;
import org.example.handler.RegistrationExceptionHandler;
import org.example.json.JacksonObjectMapper;
import org.example.service.SchedulingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchedulingControllerTest {
    private SchedulingService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(SchedulingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SchedulingController(service))
                .setControllerAdvice(new RegistrationExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonObjectMapper()))
                .build();
    }

    @Test
    void acceptsDataOnlyPlanWithoutATaskOrImage() throws Exception {
        when(service.submitDataPlan(any())).thenReturn(new SchedulingPlanAccepted(40L, "manual-1", "manual-1", "ACCEPTED"));
        mvc.perform(post("/api/v1/scheduling/data-plans").contentType("application/json")
                        .content("{\"externalPlanId\":\"manual-1\",\"assignments\":[{\"datasetId\":10,\"replicaId\":20,\"sourceNodeId\":3,\"targetNodeId\":4,\"action\":\"COPY\"}]}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.planId").value(40));
        verify(service).submitDataPlan(any());
        verify(service, never()).submit(any());
    }

    @Test
    void listsExecutionRecordsUsingTheV1Envelope() throws Exception {
        SchedulingPlan plan = SchedulingPlan.builder().planId(40L).externalPlanId("external-1")
                .taskId("external-task-1").status("FAILED").errorMessage("copy failed").build();
        when(service.listPlans("external", "FAILED", 2, 10))
                .thenReturn(new SchedulingPageResult<>(Collections.singletonList(plan), 11, 2, 10));

        mvc.perform(get("/api/v1/scheduling/plans")
                        .param("query", "external").param("status", "FAILED")
                        .param("page", "2").param("pageSize", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.list[0].externalPlanId").value("external-1"))
                .andExpect(jsonPath("$.data.list[0].errorMessage").value("copy failed"));
    }

    @Test
    void returnsAssignmentDetailsAndTimes() throws Exception {
        SchedulingPlan plan = SchedulingPlan.builder().planId(40L).status("PARTIAL_COMPLETED").build();
        SchedulingAssignment assignment = SchedulingAssignment.builder().assignmentId(50L).planId(40L)
                .datasetId(10L).replicaId(20L).sourceNodeId(3).targetNodeId(4)
                .action("COPY_AND_USE").status("FAILED").errorMessage("copy failed")
                .updatedAt(LocalDateTime.of(2026, 9, 2, 20, 30)).build();
        when(service.getPlan(40L)).thenReturn(new SchedulingPlanDetail(plan, Collections.singletonList(assignment)));

        mvc.perform(get("/api/v1/scheduling/plans/40"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.plan.status").value("PARTIAL_COMPLETED"))
                .andExpect(jsonPath("$.data.assignments[0].datasetId").value(10))
                .andExpect(jsonPath("$.data.assignments[0].targetNodeId").value(4))
                .andExpect(jsonPath("$.data.assignments[0].errorMessage").value("copy failed"))
                .andExpect(jsonPath("$.data.assignments[0].updatedAt").value("2026-09-02 20:30"));
    }

    @Test
    void returnsEmptyPagesAndClientErrors() throws Exception {
        when(service.listPlans(null, null, 1, 20))
                .thenReturn(new SchedulingPageResult<>(Collections.emptyList(), 0, 1, 20));
        when(service.getPlan(99L)).thenThrow(RegistrationException.notFound("plan not found"));
        when(service.listPlans(null, "UNKNOWN", 1, 20))
                .thenThrow(RegistrationException.invalid("unsupported status"));

        mvc.perform(get("/api/v1/scheduling/plans"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.list").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/scheduling/plans/99"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/scheduling/plans/abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/scheduling/plans").param("status", "UNKNOWN"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
