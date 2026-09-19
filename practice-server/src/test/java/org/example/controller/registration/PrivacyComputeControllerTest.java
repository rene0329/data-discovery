package org.example.controller.registration;

import org.example.exception.RegistrationException;
import org.example.handler.RegistrationExceptionHandler;
import org.example.privacy.PrivacyComputeModels.JobView;
import org.example.privacy.PrivacyComputeService;
import org.example.privacy.PrivacyPartyAuthenticator;
import org.example.service.ApiIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrivacyComputeControllerTest {
    private MockMvc mvc;
    private PrivacyComputeService service;

    @BeforeEach
    void setUp() {
        service = mock(PrivacyComputeService.class);
        PrivacyPartyAuthenticator authenticator = new PrivacyPartyAuthenticator(new MockEnvironment()
                .withProperty("privacy-computing.auth.parties.a.secret", "secret-a-123456")
                .withProperty("privacy-computing.auth.parties.b.secret", "secret-b-123456")
                .withProperty("privacy-computing.auth.parties.c.secret", "secret-c-123456"));
        PrivacyComputeController controller = new PrivacyComputeController(
                service, mock(ApiIdempotencyService.class), authenticator);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RegistrationExceptionHandler()).build();
    }

    @Test
    void jobMetadataEndpointsRequireBasicCredentials() throws Exception {
        mvc.perform(get("/api/v1/privacy-computing/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("PRIVACY_AUTH_REQUIRED"));

        mvc.perform(get("/api/v1/privacy-computing/jobs/pcj-1/events")
                        .header("Authorization", "Basic not-base64"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("PRIVACY_AUTH_INVALID"));
    }

    @Test
    void authenticatedNonParticipantGetsForbiddenInsteadOfMetadata() throws Exception {
        when(service.getAuthorized("pcj-1", "C")).thenThrow(new RegistrationException(
                HttpStatus.FORBIDDEN, "PRIVACY_JOB_ACCESS_DENIED",
                "principal is not a task participant"));

        mvc.perform(get("/api/v1/privacy-computing/jobs/pcj-1")
                        .header("Authorization", basic("C", "secret-c-123456")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PRIVACY_JOB_ACCESS_DENIED"));
    }

    @Test
    void authenticatedListIsScopedByPrincipal() throws Exception {
        when(service.listAuthorized(null, null, "A")).thenReturn(Collections.<JobView>emptyList());

        mvc.perform(get("/api/v1/privacy-computing/jobs")
                        .header("Authorization", basic("A", "secret-a-123456")))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(service).listAuthorized(null, null, "A");
    }

    private String basic(String party, String secret) {
        String value = party + ":" + secret;
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
