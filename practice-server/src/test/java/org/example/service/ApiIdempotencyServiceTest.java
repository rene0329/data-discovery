package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.OperationResult;
import org.example.entity.ApiIdempotencyRecord;
import org.example.exception.RegistrationException;
import org.example.mapper.ApiIdempotencyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiIdempotencyServiceTest {
    private ApiIdempotencyMapper mapper;
    private ApiIdempotencyService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ApiIdempotencyMapper.class);
        service = new ApiIdempotencyService(mapper, new ObjectMapper());
    }

    @Test
    void firstRequestExecutesAndStoresResponse() {
        when(mapper.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.complete(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        OperationResult result = service.execute("NODE", "VERIFY", "key-0001", "2", null,
                OperationResult.class, () -> OperationResult.completed("verified"), item -> "2");

        assertEquals("verified", result.getMessage());
        verify(mapper).complete(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void completedRequestReturnsStoredResponseWithoutExecutingAgain() throws Exception {
        when(mapper.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        ApiIdempotencyRecord record = ApiIdempotencyRecord.builder()
                .requestHash(hashFor("2", null))
                .executionStatus("COMPLETED")
                .responseJson(new ObjectMapper().writeValueAsString(OperationResult.completed("stored")))
                .build();
        when(mapper.find("key-0002", "NODE", "VERIFY")).thenReturn(record);
        AtomicBoolean executed = new AtomicBoolean(false);

        OperationResult result = service.execute("NODE", "VERIFY", "key-0002", "2", null,
                OperationResult.class, () -> {
                    executed.set(true);
                    return OperationResult.completed("new");
                }, item -> "2");

        assertFalse(executed.get());
        assertEquals("stored", result.getMessage());
    }

    @Test
    void reusedKeyWithDifferentRequestIsRejected() {
        when(mapper.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        when(mapper.find("key-0003", "NODE", "UPDATE")).thenReturn(ApiIdempotencyRecord.builder()
                .requestHash("different")
                .executionStatus("COMPLETED")
                .responseJson("{}")
                .build());

        RegistrationException exception = assertThrows(RegistrationException.class, () -> service.execute(
                "NODE", "UPDATE", "key-0003", "2", "body", OperationResult.class,
                () -> OperationResult.completed("updated"), item -> "2"));
        assertEquals("IDEMPOTENCY_KEY_REUSED", exception.getErrorCode());
    }

    @Test
    void missingReservedStateHasSpecificErrorCode() {
        when(mapper.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        when(mapper.find("key-0004", "NODE", "VERIFY")).thenReturn(null);

        RegistrationException exception = assertThrows(RegistrationException.class,
                () -> service.execute("NODE", "VERIFY", "key-0004", "2", null,
                        OperationResult.class, () -> OperationResult.completed("verified"),
                        item -> "2"));

        assertEquals("IDEMPOTENCY_STATE_UNAVAILABLE", exception.getErrorCode());
    }

    @Test
    void concurrentIdenticalRequestHasSpecificErrorCode() throws Exception {
        when(mapper.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        when(mapper.find("key-0005", "NODE", "VERIFY")).thenReturn(ApiIdempotencyRecord.builder()
                .requestHash(hashFor("2", null))
                .executionStatus("PROCESSING")
                .build());
        when(mapper.takeOverStale(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0);

        RegistrationException exception = assertThrows(RegistrationException.class,
                () -> service.execute("NODE", "VERIFY", "key-0005", "2", null,
                        OperationResult.class, () -> OperationResult.completed("verified"),
                        item -> "2"));

        assertEquals("IDEMPOTENCY_IN_PROGRESS", exception.getErrorCode());
    }

    private String hashFor(String target, Object request) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest((target + "\n" + new ObjectMapper().writeValueAsString(request))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }
}
