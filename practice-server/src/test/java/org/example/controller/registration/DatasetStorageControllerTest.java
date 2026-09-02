package org.example.controller.registration;

import org.example.dto.scheduling.DatasetStoragePlan;
import org.example.dto.scheduling.SchedulingPlanAccepted;
import org.example.entity.ApiIdempotencyRecord;
import org.example.handler.RegistrationExceptionHandler;
import org.example.json.JacksonObjectMapper;
import org.example.mapper.ApiIdempotencyMapper;
import org.example.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DatasetStorageControllerTest {
    @Test
    void exposesPolicyHeatRefreshAndReadOnlyPreview() throws Exception {
        DatasetStorageService storage = mock(DatasetStorageService.class);
        DatasetHeatService heat = mock(DatasetHeatService.class);
        MockMvc mvc = mvc(storage, heat, mock(ApiIdempotencyMapper.class));
        when(storage.policy()).thenReturn(Collections.singletonMap("heatEnabled", true));
        when(heat.refresh()).thenReturn(8);
        when(storage.preview("heat")).thenReturn(new DatasetStoragePlan());
        mvc.perform(get("/api/v1/datasets/storage-policy")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heatEnabled").value(true));
        mvc.perform(post("/api/v1/datasets/heat-refresh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updatedCount").value(8));
        mvc.perform(post("/api/v1/scheduling/storage-plans/preview").param("mode", "heat"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assignments").isEmpty());
        verify(storage, never()).submit(any());
    }

    @Test
    void lostResponseRetryReturnsOriginalPlanWithoutReplanningOrMovingAgain() throws Exception {
        DatasetStorageService storage = mock(DatasetStorageService.class);
        ApiIdempotencyMapper records = mock(ApiIdempotencyMapper.class);
        when(records.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(1, 0);
        ApiIdempotencyRecord saved = new ApiIdempotencyRecord();
        doAnswer(call -> { saved.setRequestHash(call.getArgument(3)); return 1; })
                .doAnswer(call -> 0).when(records).reserve(anyString(), anyString(), anyString(), anyString());
        when(records.complete(anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            saved.setExecutionStatus("COMPLETED");
            saved.setResponseJson(call.getArgument(4));
            return 1;
        });
        when(records.find(anyString(), anyString(), anyString())).thenReturn(saved);
        when(storage.submit(any())).thenReturn(new SchedulingPlanAccepted(7L, "storage-retry-001", null, "ACCEPTED"));
        MockMvc mvc = mvc(storage, mock(DatasetHeatService.class), records);
        String body = "{\"mode\":\"heat\",\"externalPlanId\":\"storage-retry-001\",\"assignments\":[]}";
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/v1/scheduling/storage-plans")
                .contentType("application/json").content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.planId").value(7));
        verify(storage, times(1)).submit(any());
    }

    private MockMvc mvc(DatasetStorageService storage, DatasetHeatService heat, ApiIdempotencyMapper records) {
        JacksonObjectMapper json = new JacksonObjectMapper();
        return MockMvcBuilders.standaloneSetup(new DatasetStorageController(storage, heat, new ApiIdempotencyService(records, json)))
                .setControllerAdvice(new RegistrationExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }
}
