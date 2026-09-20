package org.example.privacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProviderExecutionRequest {
    private String jobId;
    private String attemptId;
    private String templateId;
    private String protocolVersion;
    private String imageDigest;
    private String specDigest;
    private String securityProfile;
    private List<ProviderParticipant> participants = new ArrayList<>();
    private List<String> resultRecipients = new ArrayList<>();
    private Integer timeoutSeconds;
    private Map<String, Object> enginePolicy = new LinkedHashMap<>();
    private List<PrivacyComputeModels.StagingInput> staging = new ArrayList<>();

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getAttemptId() { return attemptId; }
    public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public String getImageDigest() { return imageDigest; }
    public void setImageDigest(String imageDigest) { this.imageDigest = imageDigest; }
    public String getSpecDigest() { return specDigest; }
    public void setSpecDigest(String specDigest) { this.specDigest = specDigest; }
    public String getSecurityProfile() { return securityProfile; }
    public void setSecurityProfile(String securityProfile) { this.securityProfile = securityProfile; }
    public List<ProviderParticipant> getParticipants() { return participants; }
    public void setParticipants(List<PrivacyComputeModels.ParticipantSpec> participants) {
        this.participants = new ArrayList<>();
        if (participants == null) return;
        for (PrivacyComputeModels.ParticipantSpec source : participants) {
            if (source == null) continue;
            ProviderParticipant outbound = new ProviderParticipant();
            outbound.setPartyId(source.getPartyId());
            outbound.setRole(source.getRole());
            outbound.setDatasetId(source.getDatasetId());
            outbound.setDatasetVersion(source.getDatasetVersion());
            outbound.setDatasetSha256(source.getDatasetSha256());
            outbound.setAuthoritativeSizeBytes(source.getAuthoritativeSizeBytes());
            outbound.setSchemaDigest(source.getSchemaDigest());
            outbound.setFrozenSchema(source.getFrozenSchema());
            outbound.setFields(source.getFields());
            this.participants.add(outbound);
        }
    }
    public List<String> getResultRecipients() { return resultRecipients; }
    public void setResultRecipients(List<String> resultRecipients) { this.resultRecipients = resultRecipients; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public Map<String, Object> getEnginePolicy() { return enginePolicy; }
    public void setEnginePolicy(Map<String, Object> enginePolicy) { this.enginePolicy = enginePolicy; }
    public List<PrivacyComputeModels.StagingInput> getStaging() { return staging; }
    public void setStaging(List<PrivacyComputeModels.StagingInput> staging) { this.staging = staging; }

    /** Provider wire contract intentionally excludes user, domain and control-plane slot identities. */
    public static class ProviderParticipant {
        private String partyId;
        private String role;
        private String datasetId;
        private String datasetVersion;
        private String datasetSha256;
        private Long authoritativeSizeBytes;
        private String schemaDigest;
        private List<Map<String, Object>> frozenSchema = new ArrayList<>();
        private List<String> fields = new ArrayList<>();

        public String getPartyId() { return partyId; }
        public void setPartyId(String partyId) { this.partyId = partyId; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getDatasetId() { return datasetId; }
        public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
        public String getDatasetVersion() { return datasetVersion; }
        public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
        public String getDatasetSha256() { return datasetSha256; }
        public void setDatasetSha256(String datasetSha256) { this.datasetSha256 = datasetSha256; }
        public Long getAuthoritativeSizeBytes() { return authoritativeSizeBytes; }
        public void setAuthoritativeSizeBytes(Long authoritativeSizeBytes) { this.authoritativeSizeBytes = authoritativeSizeBytes; }
        public String getSchemaDigest() { return schemaDigest; }
        public void setSchemaDigest(String schemaDigest) { this.schemaDigest = schemaDigest; }
        public List<Map<String, Object>> getFrozenSchema() { return frozenSchema; }
        public void setFrozenSchema(List<Map<String, Object>> frozenSchema) { this.frozenSchema = frozenSchema; }
        public List<String> getFields() { return fields; }
        public void setFields(List<String> fields) { this.fields = fields; }
    }
}
