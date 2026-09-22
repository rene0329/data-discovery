package org.example.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.servlet.FilterChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class InternalAgentAuthenticationFilterTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsConfiguredBearerForAgentEndpoint() throws Exception {
        InternalAgentAuthenticationFilter filter = new InternalAgentAuthenticationFilter(TOKEN, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/network/metrics/batch");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("ROLE_INTERNAL_AGENT", SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void rejectsMissingBearerForAgentEndpoint() throws Exception {
        InternalAgentAuthenticationFilter filter = new InternalAgentAuthenticationFilter(TOKEN, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/network/nodes/public-ip");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertEquals(401, response.getStatus());
    }

    @Test
    void protectsExternalNodeHeartbeatEndpoint() throws Exception {
        InternalAgentAuthenticationFilter filter = new InternalAgentAuthenticationFilter(TOKEN, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/network/nodes/heartbeat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertEquals(401, response.getStatus());
    }
}
