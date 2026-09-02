package org.example.controller.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.RegistrationException;
import org.example.handler.RegistrationExceptionHandler;
import org.example.mapper.ApiIdempotencyMapper;
import org.example.service.ApiIdempotencyService;
import org.example.service.DatasetRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatasetDeletionControllerTest {
    private DatasetRegistrationService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(DatasetRegistrationService.class);
        ApiIdempotencyMapper mapper = mock(ApiIdempotencyMapper.class);
        when(mapper.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.complete(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        ApiIdempotencyService idempotency = new ApiIdempotencyService(mapper, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new DatasetRegistrationController(service, idempotency))
                .setControllerAdvice(new RegistrationExceptionHandler()).build();
    }

    @Test
    void deleteReturnsAnOperationInTheV1Envelope() throws Exception {
        mvc.perform(delete("/api/v1/datasets/42").header("Idempotency-Key", "delete-request"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        verify(service).unregister(42L, "delete-request");
    }

    @Test
    void deleteReportsReferenceConflicts() throws Exception {
        doThrow(RegistrationException.conflict("DATASET_IN_USE", "数据集被任务引用，无法删除"))
                .when(service).unregister(42L, "delete-request");
        mvc.perform(delete("/api/v1/datasets/42").header("Idempotency-Key", "delete-request"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DATASET_IN_USE"));
    }

    @Test
    void deleteReportsMissingDataset() throws Exception {
        doThrow(RegistrationException.notFound("registered dataset not found"))
                .when(service).unregister(42L, "delete-request");
        mvc.perform(delete("/api/v1/datasets/42").header("Idempotency-Key", "delete-request"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void deleteRejectsInvalidIdsWithoutCallingService() throws Exception {
        mvc.perform(delete("/api/v1/datasets/not-an-id"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
