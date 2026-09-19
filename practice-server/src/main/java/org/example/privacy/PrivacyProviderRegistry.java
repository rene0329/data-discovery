package org.example.privacy;

import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PrivacyProviderRegistry {
    private final Map<ProviderType, PrivacyComputeProvider> providers = new EnumMap<>(ProviderType.class);

    public PrivacyProviderRegistry(List<PrivacyComputeProvider> discovered) {
        for (PrivacyComputeProvider provider : discovered) {
            if (providers.put(provider.type(), provider) != null) {
                throw new IllegalStateException("duplicate privacy provider: " + provider.type());
            }
        }
        for (ProviderType type : ProviderType.values()) {
            if (!providers.containsKey(type)) {
                throw new IllegalStateException("missing privacy provider: " + type);
            }
        }
    }

    public PrivacyComputeProvider require(ProviderType type) {
        PrivacyComputeProvider provider = providers.get(type);
        if (provider == null) throw new IllegalArgumentException("unknown provider: " + type);
        return provider;
    }

    public List<ProviderCapability> capabilities() {
        List<ProviderCapability> result = new ArrayList<>();
        for (ProviderType type : ProviderType.values()) result.add(require(type).capability());
        return result;
    }
}
