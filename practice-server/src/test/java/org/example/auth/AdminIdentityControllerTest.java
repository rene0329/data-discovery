package org.example.auth;

import org.example.json.JacksonObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminIdentityControllerTest {
    private MockMvc mvc;
    private AdminIdentityService service;

    @BeforeEach
    void setUp() {
        service = mock(AdminIdentityService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminIdentityController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonObjectMapper()))
                .setControllerAdvice(new AuthExceptionHandler()).build();
    }

    @Test
    void domainsCarryTheirSiteCode() throws Exception {
        when(service.listDomains()).thenReturn(Arrays.asList(
                domain(1L, "domain-a", "上海域（A）", "sh"), domain(6L, "domain-x", "X", null)));

        mvc.perform(get("/api/v1/admin/domains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].code").value("domain-a"))
                .andExpect(jsonPath("$.data[0].name").value("上海域（A）"))
                .andExpect(jsonPath("$.data[0].siteCode").value("sh"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[1].siteCode").value(nullValue()));
    }

    @Test
    void createPassesTheSiteCodeAndReturnsTheStoredDomain() throws Exception {
        when(service.createDomain(any())).thenReturn(domain(9L, "domain-e", "广州域", "gz"));

        mvc.perform(post("/api/v1/admin/domains").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"domain-e\",\"name\":\"广州域\",\"siteCode\":\" GZ \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.siteCode").value("gz"));

        ArgumentCaptor<AuthDtos.DomainRequest> body = ArgumentCaptor.forClass(AuthDtos.DomainRequest.class);
        verify(service).createDomain(body.capture());
        assertEquals(" GZ ", body.getValue().getSiteCode());
    }

    @Test
    void patchDistinguishesAnExplicitNullSiteFromAnAbsentOne() throws Exception {
        when(service.updateDomain(eq(2L), any())).thenReturn(domain(2L, "domain-b", "深圳域（B）", "sz"));
        ArgumentCaptor<AuthDtos.DomainRequest> body = ArgumentCaptor.forClass(AuthDtos.DomainRequest.class);

        mvc.perform(patch("/api/v1/admin/domains/2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"siteCode\":\"sz\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteCode").value("sz"));
        mvc.perform(patch("/api/v1/admin/domains/2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteCode\":null}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/domains/2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        verify(service, org.mockito.Mockito.times(3)).updateDomain(eq(2L), body.capture());
        AuthDtos.DomainRequest toggle = body.getAllValues().get(0);
        assertTrue(toggle.hasSiteCode());
        assertEquals("sz", toggle.getSiteCode());
        assertEquals(Boolean.FALSE, toggle.getEnabled());
        AuthDtos.DomainRequest clear = body.getAllValues().get(1);
        assertTrue(clear.hasSiteCode(), "explicit null clears the site");
        assertNull(clear.getSiteCode());
        AuthDtos.DomainRequest untouched = body.getAllValues().get(2);
        assertFalse(untouched.hasSiteCode(), "an absent siteCode leaves the stored site unchanged");
    }

    @Test
    void siteConflictsAndValidationErrorsCarryTheirErrorCode() throws Exception {
        when(service.createDomain(any())).thenThrow(new AuthException(HttpStatus.CONFLICT,
                "DOMAIN_SITE_TAKEN", "site 'sh' is already mapped to domain 'domain-a'"));
        mvc.perform(post("/api/v1/admin/domains").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"domain-e\",\"name\":\"E\",\"siteCode\":\"sh\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.errorCode").value("DOMAIN_SITE_TAKEN"))
                .andExpect(jsonPath("$.msg").value("site 'sh' is already mapped to domain 'domain-a'"));

        when(service.updateDomain(eq(2L), any())).thenThrow(new AuthException(HttpStatus.BAD_REQUEST,
                "INVALID_ARGUMENT", "site code must contain 1-32 lower-case letters, digits or '-'"));
        mvc.perform(patch("/api/v1/admin/domains/2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"siteCode\":\"s_h\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }

    private static CollaborationDomain domain(Long id, String code, String name, String siteCode) {
        CollaborationDomain domain = new CollaborationDomain();
        domain.setId(id);
        domain.setCode(code);
        domain.setName(name);
        domain.setSiteCode(siteCode);
        domain.setEnabled(true);
        return domain;
    }
}
