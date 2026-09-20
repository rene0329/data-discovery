package org.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.auth.InternalAgentAuthenticationFilter;
import org.example.auth.JwtAuthenticationFilter;
import org.example.vo.ApiV1Response;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfiguration {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                                   InternalAgentAuthenticationFilter internalAgentFilter,
                                                   ObjectMapper objectMapper) throws Exception {
        http.csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and().authorizeRequests()
                .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .antMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .antMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/v1/privacy-computing/capabilities",
                        "/api/v1/privacy-computing/templates").permitAll()
                .antMatchers(HttpMethod.POST, "/api/network/metrics/batch",
                        "/api/network/nodes/public-ip").hasRole("INTERNAL_AGENT")
                .antMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/v1/scheduling/storage-plans/preview")
                        .hasAnyRole("ADMIN", "DATA_OWNER")
                .antMatchers(HttpMethod.POST,
                        "/api/v1/node-discovery-runs", "/api/v1/dataset-discovery-runs",
                        "/api/v1/nodes", "/api/v1/nodes/**",
                        "/api/v1/runtime-images", "/api/v1/runtime-images/**",
                        "/api/v1/scheduling/**", "/api/v1/datasets/heat-refresh",
                        "/common/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT,
                        "/api/v1/nodes/**", "/api/v1/runtime-images/**",
                        "/api/v1/scheduling/**", "/common/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PATCH,
                        "/api/v1/nodes/**", "/api/v1/runtime-images/**",
                        "/api/v1/scheduling/**", "/common/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE,
                        "/api/v1/nodes/**", "/api/v1/runtime-images/**",
                        "/api/v1/scheduling/**", "/common/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/**").hasAnyRole("ADMIN", "DATA_OWNER")
                .antMatchers(HttpMethod.PUT, "/**").hasAnyRole("ADMIN", "DATA_OWNER")
                .antMatchers(HttpMethod.PATCH, "/**").hasAnyRole("ADMIN", "DATA_OWNER")
                .antMatchers(HttpMethod.DELETE, "/**").hasAnyRole("ADMIN", "DATA_OWNER")
                .anyRequest().authenticated()
                .and().exceptionHandling()
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getOutputStream(),
                            ApiV1Response.error(401, "AUTH_REQUIRED", "authentication is required"));
                })
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getOutputStream(),
                            ApiV1Response.error(403, "FORBIDDEN", "permission denied"));
                });
        http.addFilterBefore(internalAgentFilter, JwtAuthenticationFilter.class);
        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
