package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/** Asks the central service whether an active iperf3 measurement may use a logical link. */
@Service
public class ProbePermissionClient {
    private final RestTemplate restTemplate;
    private final String decisionUrl;
    private final String authToken;

    public ProbePermissionClient(RestTemplate restTemplate,
                                 @Value("${central.probe-decision.url:}") String decisionUrl,
                                 @Value("${central.auth-token:}") String authToken) {
        this.restTemplate = restTemplate;
        this.decisionUrl = decisionUrl == null ? "" : decisionUrl.trim();
        this.authToken = authToken == null ? "" : authToken.trim();
    }

    public Decision decide(String sourceNode, String targetNode) {
        if (decisionUrl.isEmpty() || authToken.isEmpty()) {
            return Decision.denied("CONTROL_NOT_CONFIGURED");
        }
        try {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("sourceNode", sourceNode);
            request.put("targetNode", targetNode);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(authToken);
            ResponseEntity<JsonNode> response = restTemplate.exchange(decisionUrl, HttpMethod.POST,
                    new HttpEntity<>(request, headers), JsonNode.class);
            JsonNode data = response.getBody() == null ? null : response.getBody().path("data");
            if (data == null || data.isMissingNode() || !data.path("permitted").asBoolean(false)) {
                String reason = data == null ? "INVALID_CONTROL_RESPONSE"
                        : data.path("reason").asText("PROBE_NOT_PERMITTED");
                return Decision.denied(reason);
            }
            return Decision.allowed();
        } catch (RuntimeException ex) {
            return Decision.denied("CONTROL_UNAVAILABLE");
        }
    }

    public static final class Decision {
        private final boolean permitted;
        private final String reason;

        private Decision(boolean permitted, String reason) {
            this.permitted = permitted;
            this.reason = reason;
        }

        public static Decision allowed() { return new Decision(true, "IDLE"); }
        public static Decision denied(String reason) { return new Decision(false, reason); }
        public boolean isPermitted() { return permitted; }
        public String getReason() { return reason; }
    }
}
