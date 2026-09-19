package org.example.privacy;

import org.example.privacy.PrivacyComputeModels.CapabilityStatus;
import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ProviderSubmission;
import org.example.privacy.PrivacyComputeModels.ProviderType;

import java.util.Collections;
import java.util.Map;

/** Explicit placeholder. It cannot start a job and therefore cannot be mistaken for a real engine. */
public class UnavailablePrivacyComputeProvider implements PrivacyComputeProvider {
    private final ProviderType type;
    private final String displayName;
    private final CapabilityStatus status;
    private final String reason;

    public UnavailablePrivacyComputeProvider(ProviderType type, String displayName,
                                             CapabilityStatus status, String reason) {
        this.type = type;
        this.displayName = displayName;
        this.status = status;
        this.reason = reason;
    }

    @Override public ProviderType type() { return type; }

    @Override
    public ProviderCapability capability() {
        ProviderCapability value = new ProviderCapability();
        value.setProvider(type);
        value.setDisplayName(displayName);
        value.setStatus(status);
        value.setReason(reason);
        return value;
    }

    @Override public void validate(JobSpec spec, PrivacyComputeModels.TemplateDefinition template) {
        throw new IllegalStateException(reason);
    }
    @Override public ProviderSubmission start(ProviderExecutionRequest request) {
        throw new IllegalStateException(reason);
    }
    @Override public ProviderSubmission status(String externalJobId) {
        throw new IllegalStateException(reason);
    }
    @Override public void cancel(String externalJobId, String reason) { }
    @Override public Object result(String externalJobId, String resultReference) {
        throw new IllegalStateException(this.reason);
    }
    @Override public Map<String, Object> evidence(String externalJobId) {
        return Collections.emptyMap();
    }
}
