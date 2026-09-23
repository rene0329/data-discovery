package org.example.security.access;

import org.example.auth.AuthException;
import org.example.exception.RegistrationException;
import org.example.handler.RegistrationExceptionHandler;
import org.example.json.JacksonObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Arrays;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatasetUsageControllerTest {
    private MockMvc mvc;
    private DatasetUsagePolicyService service;

    @BeforeEach
    void setUp() {
        service = mock(DatasetUsagePolicyService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DatasetUsageController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonObjectMapper()))
                .setControllerAdvice(new RegistrationExceptionHandler()).build();
    }

    @Test
    void overviewSerializesTheContractFields() throws Exception {
        DatasetUsageModels.Item granted = new DatasetUsageModels.Item();
        granted.setDatasetId(2L);
        granted.setName("B");
        granted.setDatasetCode("ds-b");
        granted.setVersion("v2");
        granted.setStatus("ACTIVE");
        granted.setOwnerDomainId(2L);
        granted.setOwnerDomainName("Domain B");
        granted.setAccessible(true);
        granted.setBasis("GRANT");
        granted.setGrantId(100L);
        granted.setGrantExpiresAt(Instant.parse("2026-09-24T09:00:00.123Z"));
        granted.setGrantReason("need B");
        DatasetUsageModels.Item denied = new DatasetUsageModels.Item();
        denied.setDatasetId(3L);
        when(service.overview()).thenReturn(new DatasetUsageModels.Overview(
                Instant.parse("2026-09-24T08:00:00.123Z"), 60L, Arrays.asList(granted, denied)));

        mvc.perform(get("/api/v1/security/dataset-access"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.serverTime").value("2026-09-24T08:00:00.123Z"))
                .andExpect(jsonPath("$.data.ttlMinutes").value(60))
                .andExpect(jsonPath("$.data.items[0].datasetId").value(2))
                .andExpect(jsonPath("$.data.items[0].name").value("B"))
                .andExpect(jsonPath("$.data.items[0].datasetCode").value("ds-b"))
                .andExpect(jsonPath("$.data.items[0].version").value("v2"))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].ownerDomainId").value(2))
                .andExpect(jsonPath("$.data.items[0].ownerDomainName").value("Domain B"))
                .andExpect(jsonPath("$.data.items[0].accessible").value(true))
                .andExpect(jsonPath("$.data.items[0].basis").value("GRANT"))
                .andExpect(jsonPath("$.data.items[0].grantId").value(100))
                .andExpect(jsonPath("$.data.items[0].grantExpiresAt").value("2026-09-24T09:00:00.123Z"))
                .andExpect(jsonPath("$.data.items[0].grantReason").value("need B"))
                .andExpect(jsonPath("$.data.items[1].accessible").value(false))
                .andExpect(jsonPath("$.data.items[1].basis").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].grantId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].grantExpiresAt").value(nullValue()));
    }

    @Test
    void grantIsCreatedWithRequestIdAndClientAddress() throws Exception {
        when(service.issueGrant(any(), anyString(), any())).thenReturn(new DatasetUsageModels.GrantIssued(
                100L, 2L, Instant.parse("2026-09-24T09:00:00.123Z"), 60L));

        mvc.perform(post("/api/v1/security/dataset-access/grants")
                        .header("X-Request-Id", "req-7")
                        .with(request -> { request.setRemoteAddr("10.1.2.3"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetId\":2,\"reason\":\"联合分析\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.grantId").value(100))
                .andExpect(jsonPath("$.data.datasetId").value(2))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-09-24T09:00:00.123Z"))
                .andExpect(jsonPath("$.data.ttlMinutes").value(60));

        ArgumentCaptor<DatasetUsageModels.GrantRequest> body =
                ArgumentCaptor.forClass(DatasetUsageModels.GrantRequest.class);
        verify(service).issueGrant(body.capture(), eq("req-7"), eq("10.1.2.3"));
        assertEquals(2L, body.getValue().getDatasetId());
        assertEquals("联合分析", body.getValue().getReason());
    }

    @Test
    void conflictsCarryTheirErrorCode() throws Exception {
        when(service.issueGrant(any(), anyString(), any())).thenThrow(
                RegistrationException.conflict("GRANT_ALREADY_ACTIVE", "an unexpired grant already exists"));

        mvc.perform(post("/api/v1/security/dataset-access/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetId\":2,\"reason\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.errorCode").value("GRANT_ALREADY_ACTIVE"));
    }

    @Test
    void validationErrorsAreBadRequests() throws Exception {
        when(service.issueGrant(any(), anyString(), any())).thenThrow(new RegistrationException(
                HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "reason is required"));

        mvc.perform(post("/api/v1/security/dataset-access/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetId\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.msg").value("reason is required"));

        mvc.perform(post("/api/v1/security/dataset-access/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_JSON"));
    }

    @Test
    void missingUserIsUnauthorized() throws Exception {
        when(service.overview()).thenThrow(new AuthException(HttpStatus.UNAUTHORIZED,
                "AUTH_REQUIRED", "authentication is required"));

        mvc.perform(get("/api/v1/security/dataset-access"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_REQUIRED"));
    }
}
