package org.example.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImpersonationWriteGuardFilterTest {
    private final ImpersonationWriteGuardFilter filter = new ImpersonationWriteGuardFilter(new ObjectMapper());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blocksWritesAndPlaintextResultsButAllowsExit() throws Exception {
        AuthenticatedUser user = new AuthenticatedUser(5L, "owner-a", "Owner A",
                Collections.singleton("DATA_OWNER"), 1L, "domain-a", "Domain A",
                true, 1L, "admin");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList()));

        assertEquals(403, execute("POST", "/api/v1/privacy-computing/jobs"));
        assertEquals(403, execute("GET", "/api/v1/privacy-computing/jobs/job-1/result"));
        assertEquals(204, execute("POST", "/api/v1/auth/impersonation/exit"));
        assertEquals(204, execute("GET", "/api/v1/privacy-computing/jobs"));
    }

    private int execute(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));
        return response.getStatus();
    }
}
