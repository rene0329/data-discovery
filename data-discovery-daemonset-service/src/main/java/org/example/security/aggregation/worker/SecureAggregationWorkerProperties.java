package org.example.security.aggregation.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.secure-aggregation-worker")
public class SecureAggregationWorkerProperties {
    private boolean enabled = true;
    private String participantId;
    private String authSecret = "topic4-aggregation-worker-secret";
    private String inputFile;
    private long runTtlSeconds = 300;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getParticipantId() { return participantId; }
    public void setParticipantId(String participantId) { this.participantId = participantId; }
    public String getAuthSecret() { return authSecret; }
    public void setAuthSecret(String authSecret) { this.authSecret = authSecret; }
    public String getInputFile() { return inputFile; }
    public void setInputFile(String inputFile) { this.inputFile = inputFile; }
    public long getRunTtlSeconds() { return runTtlSeconds; }
    public void setRunTtlSeconds(long runTtlSeconds) { this.runTtlSeconds = runTtlSeconds; }
}
