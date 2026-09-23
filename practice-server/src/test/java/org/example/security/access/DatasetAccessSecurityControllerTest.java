package org.example.security.access;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatasetAccessSecurityControllerTest {
    private MockMvc mvc;
    private DatasetAccessAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = mock(DatasetAccessAuthorizationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DatasetAccessSecurityController(service)).build();
    }

    @Test
    void eventsForwardTheOptionalDecisionFilter() throws Exception {
        DatasetAccessAuditEvent denied = new DatasetAccessAuditEvent();
        denied.setDecision("DENIED");
        when(service.findAuditEvents(null, null, null, "DENIED", 20))
                .thenReturn(Collections.singletonList(denied));

        mvc.perform(get("/api/v1/security/access/events").param("decision", "DENIED").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].decision").value("DENIED"));
    }

    @Test
    void existingEventParametersKeepWorkingWithoutDecision() throws Exception {
        mvc.perform(get("/api/v1/security/access/events").param("runId", "run-1").param("principal", "owner-a"))
                .andExpect(status().isOk());

        verify(service).findAuditEvents(null, "run-1", "owner-a", null, 100);
    }
}
