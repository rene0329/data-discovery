package org.example.security.aggregation;

import java.util.List;
import java.util.Map;

/** JSON wire types. They intentionally contain no private key, mask, or local value fields. */
public final class SecureAggregationWire {
    private SecureAggregationWire() {
    }

    public static class PrepareRequest {
        private String runId;
        private List<String> participantIds;

        public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
        public List<String> getParticipantIds() { return participantIds; }
        public void setParticipantIds(List<String> participantIds) { this.participantIds = participantIds; }
    }

    public static class PrepareResponse {
        private String runId;
        private String participantId;
        private String publicKey;
        private String protocolVersion;

        public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
        public String getParticipantId() { return participantId; }
        public void setParticipantId(String participantId) { this.participantId = participantId; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    }

    public static class ContributionRequest {
        private String runId;
        private List<String> participantIds;
        private Map<String, String> publicKeys;

        public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
        public List<String> getParticipantIds() { return participantIds; }
        public void setParticipantIds(List<String> participantIds) { this.participantIds = participantIds; }
        public Map<String, String> getPublicKeys() { return publicKeys; }
        public void setPublicKeys(Map<String, String> publicKeys) { this.publicKeys = publicKeys; }
    }

    public static class ContributionResponse {
        private String runId;
        private String participantId;
        private String maskedValue;
        private String protocolVersion;

        public String getRunId() { return runId; }
        public void setRunId(String runId) { this.runId = runId; }
        public String getParticipantId() { return participantId; }
        public void setParticipantId(String participantId) { this.participantId = participantId; }
        public String getMaskedValue() { return maskedValue; }
        public void setMaskedValue(String maskedValue) { this.maskedValue = maskedValue; }
        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    }
}
