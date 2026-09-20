package org.example.controller.registration;

import org.example.auth.AuthenticatedUser;
import org.example.auth.CurrentUserService;
import org.example.exception.RegistrationException;
import org.example.handler.RegistrationExceptionHandler;
import org.example.privacy.PrivacyComputeModels.JobView;
import org.example.privacy.PrivacyComputeService;
import org.example.service.ApiIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrivacyComputeControllerTest {
    private MockMvc mvc;
    private PrivacyComputeService service;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        service = mock(PrivacyComputeService.class);
        CurrentUserService currentUsers = mock(CurrentUserService.class);
        user = new AuthenticatedUser(7L, "owner-a", "Owner A",
                new LinkedHashSet<>(Arrays.asList("DATA_OWNER")), 1L, "DOMAIN_A", "Domain A");
        when(currentUsers.require()).thenReturn(user);
        PrivacyComputeController controller = new PrivacyComputeController(
                service, mock(ApiIdempotencyService.class), currentUsers);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RegistrationExceptionHandler()).build();
    }

    @Test
    void authenticatedNonParticipantGetsForbiddenInsteadOfMetadata() throws Exception {
        when(service.getAuthorized("pcj-1", user)).thenThrow(new RegistrationException(
                HttpStatus.FORBIDDEN, "PRIVACY_JOB_ACCESS_DENIED",
                "principal is not a task participant"));

        mvc.perform(get("/api/v1/privacy-computing/jobs/pcj-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PRIVACY_JOB_ACCESS_DENIED"));
    }

    @Test
    void authenticatedListIsScopedByPrincipal() throws Exception {
        when(service.listAuthorized(null, null, user)).thenReturn(Collections.<JobView>emptyList());

        mvc.perform(get("/api/v1/privacy-computing/jobs"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(service).listAuthorized(null, null, user);
    }
}
