package org.example.controller.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.OperationResult;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatasetReplicaRemovalControllerTest {
    private DatasetRegistrationService service;
    private ApiIdempotencyMapper idempotencyMapper;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(DatasetRegistrationService.class);
        idempotencyMapper = mock(ApiIdempotencyMapper.class);
        when(idempotencyMapper.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(idempotencyMapper.complete(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        ApiIdempotencyService idempotency = new ApiIdempotencyService(idempotencyMapper, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new DatasetRegistrationController(service, idempotency))
                .setControllerAdvice(new RegistrationExceptionHandler()).build();
    }

    @Test
    void removeReplicaReturnsAnOperationInTheV1Envelope() throws Exception {
        when(service.removeReplica(42L, 100L, "remove-replica-1"))
                .thenReturn(OperationResult.completed("副本已删除，节点上的文件已删除"));

        mvc.perform(delete("/api/v1/datasets/42/replicas/100").header("Idempotency-Key", "remove-replica-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        verify(service).removeReplica(42L, 100L, "remove-replica-1");
        verify(idempotencyMapper).reserve(eq("remove-replica-1"), eq("DATASET"), eq("REMOVE_REPLICA"), anyString());
        verify(idempotencyMapper).complete(eq("remove-replica-1"), eq("DATASET"), eq("REMOVE_REPLICA"),
                eq("100"), anyString());
    }

    @Test
    void lastUsableReplicaIsAConflictWithAStableErrorCode() throws Exception {
        when(service.removeReplica(42L, 100L, "remove-replica-2")).thenThrow(
                RegistrationException.conflict("LAST_USABLE_REPLICA", "不能删除数据集最后一个可用副本"));

        mvc.perform(delete("/api/v1/datasets/42/replicas/100").header("Idempotency-Key", "remove-replica-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.errorCode").value("LAST_USABLE_REPLICA"))
                .andExpect(jsonPath("$.msg").value("不能删除数据集最后一个可用副本"));
        verify(idempotencyMapper).release("remove-replica-2", "DATASET", "REMOVE_REPLICA");
    }

    @Test
    void foreignReplicaIsNotFound() throws Exception {
        when(service.removeReplica(42L, 100L, "remove-replica-3")).thenThrow(
                RegistrationException.notFound("REPLICA_NOT_FOUND", "dataset replica not found"));

        mvc.perform(delete("/api/v1/datasets/42/replicas/100").header("Idempotency-Key", "remove-replica-3"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REPLICA_NOT_FOUND"));
    }

    @Test
    void invalidReplicaIdIsRejectedWithoutCallingTheService() throws Exception {
        mvc.perform(delete("/api/v1/datasets/42/replicas/not-an-id"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
