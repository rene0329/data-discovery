package org.example.privacy;

import org.example.privacy.PrivacyComputeModels.JobSpec;
import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ProviderSubmission;
import org.example.privacy.PrivacyComputeModels.ProviderType;

import java.util.Map;

/**
 * Runtime boundary between Topic4's control plane and an actual privacy engine.
 * Implementations must never silently select a weaker protocol than the requested template.
 */
public interface PrivacyComputeProvider {
    ProviderType type();

    ProviderCapability capability();

    /** Provider-specific validation that does not start a computation. */
    void validate(JobSpec spec, PrivacyComputeModels.TemplateDefinition template);

    ProviderSubmission start(ProviderExecutionRequest request);

    ProviderSubmission status(String externalJobId);

    void cancel(String externalJobId, String reason);

    Object result(String externalJobId, String resultReference);

    Map<String, Object> evidence(String externalJobId);
}
