package org.example.privacy;

import org.example.privacy.PrivacyComputeModels.JobSpec;

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
    private List<PrivacyComputeModels.ParticipantSpec> participants = new ArrayList<>();
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
    public List<PrivacyComputeModels.ParticipantSpec> getParticipants() { return participants; }
    public void setParticipants(List<PrivacyComputeModels.ParticipantSpec> participants) { this.participants = participants; }
    public List<String> getResultRecipients() { return resultRecipients; }
    public void setResultRecipients(List<String> resultRecipients) { this.resultRecipients = resultRecipients; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public Map<String, Object> getEnginePolicy() { return enginePolicy; }
    public void setEnginePolicy(Map<String, Object> enginePolicy) { this.enginePolicy = enginePolicy; }
    public List<PrivacyComputeModels.StagingInput> getStaging() { return staging; }
    public void setStaging(List<PrivacyComputeModels.StagingInput> staging) { this.staging = staging; }
}
