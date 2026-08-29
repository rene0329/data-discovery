package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.ApiIdempotencyRecord;
import org.example.exception.RegistrationException;
import org.example.mapper.ApiIdempotencyMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class ApiIdempotencyService {
    private final ApiIdempotencyMapper mapper;
    private final ObjectMapper objectMapper;

    public ApiIdempotencyService(ApiIdempotencyMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public <T> T execute(String resourceType, String action, String key, String target,
                         Object request, Class<T> responseType, Supplier<T> operation,
                         Function<T, String> resourceIdExtractor) {
        validateKey(key);
        String requestHash = hash(target, request);
        if (mapper.reserve(key, resourceType, action, requestHash) == 0) {
            ApiIdempotencyRecord existing = mapper.find(key, resourceType, action);
            if (existing == null) {
                throw RegistrationException.conflict("idempotent request state is unavailable; retry later");
            }
            if (!requestHash.equals(existing.getRequestHash())) {
                throw RegistrationException.conflict(
                        "Idempotency-Key was already used with a different request");
            }
            if ("COMPLETED".equals(existing.getExecutionStatus())) {
                return read(existing.getResponseJson(), responseType);
            }
            if (mapper.takeOverStale(key, resourceType, action, requestHash) != 1) {
                throw RegistrationException.conflict("an identical request is still being processed");
            }
        }

        try {
            T result = operation.get();
            String resourceId = resourceIdExtractor == null ? null : resourceIdExtractor.apply(result);
            String responseJson = write(result);
            if (mapper.complete(key, resourceType, action, resourceId, responseJson) != 1) {
                throw new IllegalStateException("failed to complete idempotent request");
            }
            return result;
        } catch (RegistrationException ex) {
            mapper.release(key, resourceType, action);
            throw ex;
        } catch (RuntimeException ex) {
            mapper.release(key, resourceType, action);
            throw ex;
        }
    }

    private void validateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw RegistrationException.invalid("Idempotency-Key must not be empty");
        }
        if (key.length() < 8) {
            throw RegistrationException.invalid("Idempotency-Key must contain at least 8 characters");
        }
        if (key.length() > 128) {
            throw RegistrationException.invalid("Idempotency-Key must not exceed 128 characters");
        }
    }

    private String hash(String target, Object request) {
        String value = (target == null ? "" : target) + "\n" + write(request);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw RegistrationException.invalid("request body cannot be normalized");
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored idempotent response cannot be read", ex);
        }
    }
}
