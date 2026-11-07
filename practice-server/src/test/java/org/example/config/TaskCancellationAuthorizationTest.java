package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.auth.AuthMapper;
import org.example.auth.AuthService;
import org.example.auth.InternalAgentAuthenticationFilter;
import org.example.auth.JwtAuthenticationFilter;
import org.example.auth.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.FilterInvocation;
import org.springframework.security.web.access.intercept.FilterSecurityInterceptor;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import javax.servlet.FilterChain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** Exercises the real HTTP authorization matcher ordering, without a database or Kubernetes. */
class TaskCancellationAuthorizationTest {
    private AnnotationConfigWebApplicationContext context;

    @BeforeEach
    void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void onlyAdministratorCanCancelAnyOrdinaryTask() {
        assertDoesNotThrow(() -> authorize("POST", "/api/v1/tasks/42/cancel", "ADMIN"));
        assertThrows(AccessDeniedException.class,
                () -> authorize("POST", "/api/v1/tasks/42/cancel", "DATA_OWNER"));
        assertThrows(AccessDeniedException.class,
                () -> authorize("POST", "/api/v1/tasks/42/cancel", "AUDITOR"));
    }

    @Test
    void domainUsersCanStillCreateTasksAndAuthenticatedUsersCanReadJobs() {
        assertDoesNotThrow(() -> authorize("POST", "/api/v1/tasks", "DATA_OWNER"));
        assertDoesNotThrow(() -> authorize("GET", "/api/v1/tasks/42/jobs", "AUDITOR"));
    }

    private void authorize(String method, String path, String role) {
        FilterChainProxy proxy = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        FilterSecurityInterceptor interceptor = proxy.getFilters(path).stream()
                .filter(FilterSecurityInterceptor.class::isInstance)
                .map(FilterSecurityInterceptor.class::cast).findFirst().get();
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        FilterInvocation invocation = new FilterInvocation(request, new MockHttpServletResponse(), mock(FilterChain.class));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "test-user", null, AuthorityUtils.createAuthorityList("ROLE_" + role));
        interceptor.getAccessDecisionManager().decide(authentication, invocation,
                interceptor.getSecurityMetadataSource().getAttributes(invocation));
    }

    @Configuration
    @EnableWebSecurity
    @Import(SecurityConfiguration.class)
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean JwtAuthenticationFilter jwtFilter() {
            return new JwtAuthenticationFilter(mock(JwtTokenService.class), mock(AuthMapper.class), mock(AuthService.class));
        }
        @Bean InternalAgentAuthenticationFilter internalFilter(ObjectMapper json) {
            return new InternalAgentAuthenticationFilter("test-internal-agent-key", json);
        }
    }
}
