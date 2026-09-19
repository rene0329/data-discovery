package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.privacy.PrivacyComputeModels.CapabilityStatus;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ProviderSubmission;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP adapter implemented by the separately deployed runtime wrappers.
 * A runtime is AVAILABLE only when /health supplies both an exact version and an immutable sha256 digest.
 */
public class HttpPrivacyComputeProvider implements PrivacyComputeProvider {
    private static final int START_ATTEMPTS = 3;
    private static final long START_RETRY_DELAY_MILLIS = 200L;
    private final ProviderType type;
    private final String displayName;
    private final String baseUrl;
    private final String bearerToken;
    private final boolean experimental;
    private final List<String> securityProfiles;
    private final List<String> operations;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpPrivacyComputeProvider(ProviderType type, String displayName, String baseUrl,
                                      String bearerToken, boolean experimental,
                                      List<String> securityProfiles, List<String> operations,
                                      RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.type = type;
        this.displayName = displayName;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.bearerToken = bearerToken;
        this.experimental = experimental;
        this.securityProfiles = securityProfiles;
        this.operations = operations;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override public ProviderType type() { return type; }

    @Override
    @SuppressWarnings("unchecked")
    public ProviderCapability capability() {
        ProviderCapability capability = baseCapability();
        if (baseUrl.isEmpty()) {
            capability.setStatus(CapabilityStatus.UNAVAILABLE);
            capability.setReason("runtime endpoint is not configured");
            return capability;
        }
        try {
            ResponseEntity<Map> response = restTemplate.exchange(uri("/health"), HttpMethod.GET,
                    entity(null), Map.class);
            Map<String, Object> body = response.getBody();
            String version = text(body, "version");
            String digest = text(body, "imageDigest");
            String status = text(body, "status");
            if (!response.getStatusCode().is2xxSuccessful() || body == null
                    || !"UP".equalsIgnoreCase(status) || blank(version)
                    || blank(digest) || !digest.matches("sha256:[0-9a-fA-F]{64}")) {
                capability.setStatus(CapabilityStatus.UNAVAILABLE);
                capability.setReason("runtime health is missing UP status, exact version, or immutable imageDigest");
                return capability;
            }
            List<String> runtimeOperations = stringList(body.get("controlPlaneOperations"));
            List<String> runtimeProfiles = stringList(body.get("securityProfiles"));
            if (!runtimeOperations.containsAll(operations)
                    || !runtimeProfiles.containsAll(securityProfiles)) {
                capability.setStatus(CapabilityStatus.UNAVAILABLE);
                capability.setReason("runtime health does not expose every configured operation and security profile");
                return capability;
            }
            capability.setStatus(CapabilityStatus.AVAILABLE);
            capability.setVersion(version);
            capability.setImageDigest(digest.toLowerCase());
            capability.setOperations(runtimeOperations);
            capability.setSecurityProfiles(runtimeProfiles);
            return capability;
        } catch (RestClientException | IllegalArgumentException ex) {
            capability.setStatus(CapabilityStatus.UNAVAILABLE);
            capability.setReason("runtime health check failed: " + safe(ex.getMessage()));
            return capability;
        }
    }

    @Override
    public void validate(JobSpec spec, PrivacyComputeModels.TemplateDefinition template) {
        ProviderCapability current = capability();
        if (current.getStatus() != CapabilityStatus.AVAILABLE) {
            throw new IllegalStateException(current.getReason());
        }
        if (!securityProfiles.contains(template.getSecurityProfile())) {
            throw new IllegalStateException("runtime does not advertise the template security profile");
        }
        if (!operations.contains(template.getOperation())) {
            throw new IllegalStateException("runtime does not advertise the template operation");
        }
    }

    @Override
    public ProviderSubmission start(ProviderExecutionRequest request) {
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            try {
                return exchange("/jobs", HttpMethod.POST, request, ProviderSubmission.class);
            } catch (HttpClientErrorException ex) {
                // Contract/authentication errors are deterministic and must not be replayed.
                throw ex;
            } catch (RestClientException ex) {
                // POST /jobs is idempotent by jobId/attemptId.  A retry recovers the
                // common case where the runtime accepted a job but its response was lost.
                lastFailure = ex;
                if (attempt == START_ATTEMPTS) break;
                try {
                    Thread.sleep(START_RETRY_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("privacy runtime start retry was interrupted", interrupted);
                }
            }
        }
        throw lastFailure;
    }

    @Override
    public ProviderSubmission status(String externalJobId) {
        return exchange("/jobs/" + path(externalJobId), HttpMethod.GET, null, ProviderSubmission.class);
    }

    @Override
    public void cancel(String externalJobId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        exchange("/jobs/" + path(externalJobId) + "/cancel", HttpMethod.POST, body, Map.class);
    }

    @Override
    public Object result(String externalJobId, String resultReference) {
        return exchange("/jobs/" + path(externalJobId) + "/result", HttpMethod.GET,
                null, Object.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> evidence(String externalJobId) {
        Map<String, Object> value = exchange("/jobs/" + path(externalJobId) + "/evidence",
                HttpMethod.GET, null, Map.class);
        return value == null ? Collections.emptyMap() : value;
    }

    private ProviderCapability baseCapability() {
        ProviderCapability value = new ProviderCapability();
        value.setProvider(type);
        value.setDisplayName(displayName);
        value.setSecurityProfiles(securityProfiles);
        value.setOperations(operations);
        value.setExperimental(experimental);
        return value;
    }

    private <T> T exchange(String suffix, HttpMethod method, Object body, Class<T> type) {
        ResponseEntity<T> response = restTemplate.exchange(uri(suffix), method, entity(body), type);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("runtime returned HTTP " + response.getStatusCodeValue());
        }
        return response.getBody();
    }

    private HttpEntity<Object> entity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (!blank(bearerToken)) headers.setBearerAuth(bearerToken.trim());
        return new HttpEntity<>(body, headers);
    }

    private URI uri(String suffix) {
        return URI.create(baseUrl + suffix);
    }

    private String path(String value) {
        if (blank(value) || !value.matches("[A-Za-z0-9._:-]{1,160}")) {
            throw new IllegalArgumentException("invalid external job id");
        }
        return value;
    }

    private String text(Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) return null;
        return String.valueOf(map.get(key));
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List)) return Collections.emptyList();
        List<String> values = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (item instanceof String && !blank((String) item)) values.add((String) item);
        }
        return values;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static String safe(String value) {
        if (blank(value)) return "unreachable";
        String normalized = value.replaceAll("[\\r\\n\\t]", " ");
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }
}
