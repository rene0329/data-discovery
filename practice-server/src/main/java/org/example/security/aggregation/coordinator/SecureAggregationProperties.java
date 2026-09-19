package org.example.security.aggregation.coordinator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.secure-aggregation")
public class SecureAggregationProperties {
    private boolean enabled = true;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 10000;
    private List<Participant> participants = new ArrayList<>(Arrays.asList(
            new Participant("A", "http://10.212.14.88:8080", "topic4-aggregation-worker-secret"),
            new Participant("B", "http://10.212.14.89:8080", "topic4-aggregation-worker-secret"),
            new Participant("C", "http://10.212.14.90:8080", "topic4-aggregation-worker-secret")
    ));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public List<Participant> getParticipants() { return participants; }
    public void setParticipants(List<Participant> participants) { this.participants = participants; }

    public static class Participant {
        private String id;
        private String baseUrl;
        private String authSecret;

        public Participant() {
        }

        public Participant(String id, String baseUrl, String authSecret) {
            this.id = id;
            this.baseUrl = baseUrl;
            this.authSecret = authSecret;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getAuthSecret() { return authSecret; }
        public void setAuthSecret(String authSecret) { this.authSecret = authSecret; }
    }
}
