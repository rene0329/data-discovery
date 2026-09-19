package org.example.privacy;

import org.example.privacy.PrivacyComputeModels.CapabilityStatus;
import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivacyTemplateCatalogTest {
    @Test
    void doesNotAdvertiseUnimplementedOperationsWhenSharedProviderIsHealthy() {
        PrivacyProviderRegistry registry = mock(PrivacyProviderRegistry.class);
        ProviderCapability capability = new ProviderCapability();
        capability.setProvider(ProviderType.KUSCIA_SECRETFLOW);
        capability.setStatus(CapabilityStatus.AVAILABLE);
        capability.setOperations(Arrays.asList("PSI_2P", "PSI_3P"));
        capability.setImageDigest("sha256:" + repeat('a', 64));
        when(registry.capabilities()).thenReturn(Collections.singletonList(capability));

        PrivacyTemplateCatalog catalog = new PrivacyTemplateCatalog(registry);

        assertTrue(find(catalog, "psi-2p-v1").isAvailable());
        assertFalse(find(catalog, "he-paillier-2p-v1").isAvailable());
        assertFalse(find(catalog, "vfl-secureboost-2p-v1").isAvailable());
    }

    private TemplateDefinition find(PrivacyTemplateCatalog catalog, String id) {
        for (TemplateDefinition item : catalog.list()) {
            if (id.equals(item.getTemplateId())) return item;
        }
        throw new AssertionError("template missing: " + id);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
