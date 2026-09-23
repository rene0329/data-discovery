package org.example.auth;

import org.example.service.DatasetRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminIdentityServiceTest {
    private AuthMapper mapper;
    private AdminIdentityService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AuthMapper.class);
        service = new AdminIdentityService(mapper, new BCryptPasswordEncoder(4),
                mock(DatasetRegistrationService.class));
        doAnswer(inv -> {
            inv.<CollaborationDomain>getArgument(0).setId(9L);
            return 1;
        }).when(mapper).insertDomain(any());
    }

    // ---- create ----------------------------------------------------------------

    @Test
    void createDomainStoresTheTrimmedLowerCaseSite() {
        CollaborationDomain stored = domain(9L, "domain-e", "gz");
        when(mapper.findDomainById(9L)).thenReturn(stored);

        CollaborationDomain created = service.createDomain(request("domain-e", "广州域", "  GZ-1 "));

        CollaborationDomain inserted = insertedDomain();
        assertEquals("gz-1", inserted.getSiteCode());
        assertEquals("domain-e", inserted.getCode());
        verify(mapper).findDomainBySiteCode("gz-1");
        assertEquals(stored, created);
    }

    @Test
    void createDomainWithoutOrWithBlankSiteStoresNull() {
        service.createDomain(request("domain-e", "E", null));
        assertNull(insertedDomain().getSiteCode());
        verify(mapper, never()).findDomainBySiteCode(anyString());

        setUp();
        service.createDomain(request("domain-f", "F", "   "));
        assertNull(insertedDomain().getSiteCode());
        verify(mapper, never()).findDomainBySiteCode(anyString());
    }

    @Test
    void createDomainRejectsInvalidSiteCodes() {
        for (String site : new String[]{"s_h", "上海", "a b", "sh.1", repeat("a", 33)}) {
            AuthException error = assertThrows(AuthException.class,
                    () -> service.createDomain(request("domain-e", "E", site)), site);
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatus(), site);
            assertEquals("INVALID_ARGUMENT", error.getErrorCode(), site);
        }
        verify(mapper, never()).insertDomain(any());

        service.createDomain(request("domain-e", "E", repeat("a", 32)));
        assertEquals(repeat("a", 32), insertedDomain().getSiteCode());
    }

    @Test
    void createDomainWithASiteMappedToAnotherDomainIsConflict() {
        when(mapper.findDomainBySiteCode("sh")).thenReturn(domain(1L, "domain-a", "sh"));

        AuthException error = assertThrows(AuthException.class,
                () -> service.createDomain(request("domain-e", "E", "SH")));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("DOMAIN_SITE_TAKEN", error.getErrorCode());
        assertTrue(error.getMessage().contains("domain-a"), error.getMessage());
        verify(mapper, never()).insertDomain(any());
    }

    @Test
    void createDomainRaceOnTheUniqueKeysMapsToTheMatchingConflict() {
        doAnswer(inv -> {
            throw new DuplicateKeyException("Duplicate entry 'sh' for key 'uk_collaboration_domain_site'");
        }).when(mapper).insertDomain(any());
        AuthException site = assertThrows(AuthException.class,
                () -> service.createDomain(request("domain-e", "E", "sh")));
        assertEquals(HttpStatus.CONFLICT, site.getStatus());
        assertEquals("DOMAIN_SITE_TAKEN", site.getErrorCode());

        doAnswer(inv -> {
            throw new DuplicateKeyException("Duplicate entry 'domain-e' for key 'uk_collaboration_domain_code'");
        }).when(mapper).insertDomain(any());
        AuthException code = assertThrows(AuthException.class,
                () -> service.createDomain(request("domain-e", "E", "sh")));
        assertEquals("DOMAIN_CODE_EXISTS", code.getErrorCode());
    }

    // ---- update ------------------------------------------------------------------

    @Test
    void updateDomainSetsTheNormalizedSite() {
        when(mapper.findDomainById(2L)).thenReturn(domain(2L, "domain-b", null));

        service.updateDomain(2L, request(null, null, " SZ "));

        assertEquals("sz", updatedDomain().getSiteCode());
    }

    @Test
    void updateDomainWithItsOwnCurrentSiteIsNotAConflict() {
        // The enable/disable toggle PATCHes { enabled, siteCode: <current site> }.
        CollaborationDomain current = domain(2L, "domain-b", "sz");
        when(mapper.findDomainById(2L)).thenReturn(current);
        when(mapper.findDomainBySiteCode("sz")).thenReturn(domain(2L, "domain-b", "sz"));
        AuthDtos.DomainRequest toggle = request(null, null, "sz");
        toggle.setEnabled(false);

        service.updateDomain(2L, toggle);

        CollaborationDomain updated = updatedDomain();
        assertEquals("sz", updated.getSiteCode());
        assertEquals(Boolean.FALSE, updated.getEnabled());
    }

    @Test
    void updateDomainWithASiteMappedToAnotherDomainIsConflict() {
        when(mapper.findDomainById(2L)).thenReturn(domain(2L, "domain-b", "sz"));
        when(mapper.findDomainBySiteCode("sh")).thenReturn(domain(1L, "domain-a", "sh"));

        AuthException error = assertThrows(AuthException.class,
                () -> service.updateDomain(2L, request(null, null, "sh")));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("DOMAIN_SITE_TAKEN", error.getErrorCode());
        verify(mapper, never()).updateDomain(any());
    }

    @Test
    void updateDomainWithExplicitNullOrBlankSiteClearsIt() {
        when(mapper.findDomainById(2L)).thenReturn(domain(2L, "domain-b", "sz"));

        service.updateDomain(2L, request(null, null, null));

        assertNull(updatedDomain().getSiteCode());
        verify(mapper, never()).findDomainBySiteCode(anyString());

        setUp();
        when(mapper.findDomainById(2L)).thenReturn(domain(2L, "domain-b", "sz"));
        service.updateDomain(2L, request(null, null, ""));
        assertNull(updatedDomain().getSiteCode());
    }

    @Test
    void updateDomainThatOmitsSiteCodeKeepsTheStoredSite() {
        when(mapper.findDomainById(2L)).thenReturn(domain(2L, "domain-b", "sz"));
        AuthDtos.DomainRequest rename = new AuthDtos.DomainRequest();
        rename.setName("深圳域（B）");

        service.updateDomain(2L, rename);

        CollaborationDomain updated = updatedDomain();
        assertEquals("sz", updated.getSiteCode());
        assertEquals("深圳域（B）", updated.getName());
        verify(mapper, never()).findDomainBySiteCode(anyString());
    }

    @Test
    void updateDomainRejectsAnInvalidSite() {
        when(mapper.findDomainById(2L)).thenReturn(domain(2L, "domain-b", "sz"));

        AuthException error = assertThrows(AuthException.class,
                () -> service.updateDomain(2L, request(null, null, "s z")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("INVALID_ARGUMENT", error.getErrorCode());
        verify(mapper, never()).updateDomain(any());
    }

    @Test
    void updateDomainRaceOnTheSiteKeyIsConflict() {
        when(mapper.findDomainById(2L)).thenReturn(domain(2L, "domain-b", null));
        when(mapper.updateDomain(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry 'sh' for key 'uk_collaboration_domain_site'"));

        AuthException error = assertThrows(AuthException.class,
                () -> service.updateDomain(2L, request(null, null, "sh")));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("DOMAIN_SITE_TAKEN", error.getErrorCode());
    }

    // ---- helpers -------------------------------------------------------------------

    private CollaborationDomain insertedDomain() {
        ArgumentCaptor<CollaborationDomain> captor = ArgumentCaptor.forClass(CollaborationDomain.class);
        verify(mapper).insertDomain(captor.capture());
        return captor.getValue();
    }

    private CollaborationDomain updatedDomain() {
        ArgumentCaptor<CollaborationDomain> captor = ArgumentCaptor.forClass(CollaborationDomain.class);
        verify(mapper).updateDomain(captor.capture());
        return captor.getValue();
    }

    private static AuthDtos.DomainRequest request(String code, String name, String siteCode) {
        AuthDtos.DomainRequest request = new AuthDtos.DomainRequest();
        request.setCode(code);
        request.setName(name);
        request.setSiteCode(siteCode);
        return request;
    }

    private static CollaborationDomain domain(Long id, String code, String siteCode) {
        CollaborationDomain domain = new CollaborationDomain();
        domain.setId(id);
        domain.setCode(code);
        domain.setName(code);
        domain.setSiteCode(siteCode);
        domain.setEnabled(true);
        return domain;
    }

    private static String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) builder.append(value);
        return builder.toString();
    }
}
