package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.privacy.PrivacyComputeModels.CapabilityStatus;
import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpPrivacyComputeProviderTest {
    @Test
    void rejectsUpRuntimeThatOmitsAConfiguredOperation() {
        RestTemplate client = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo("http://runtime/health")).andRespond(withSuccess(
                health("[\"PSI_2P\"]", "[\"SEMI_HONEST\",\"SEMI_HONEST_HE\"]"),
                MediaType.APPLICATION_JSON));
        HttpPrivacyComputeProvider provider = provider(client);

        ProviderCapability capability = provider.capability();

        assertEquals(CapabilityStatus.UNAVAILABLE, capability.getStatus());
        assertTrue(capability.getReason().contains("operation and security profile"));
        server.verify();
    }

    @Test
    void advertisesOnlyAfterRuntimeConfirmsEveryConfiguredCapability() {
        RestTemplate client = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo("http://runtime/health")).andRespond(withSuccess(
                health("[\"PSI_2P\",\"PSI_3P\",\"HE_PAILLIER\",\"VFL_SECUREBOOST\"]",
                        "[\"SEMI_HONEST\",\"SEMI_HONEST_HE\"]"), MediaType.APPLICATION_JSON));
        HttpPrivacyComputeProvider provider = provider(client);

        ProviderCapability capability = provider.capability();

        assertEquals(CapabilityStatus.AVAILABLE, capability.getStatus());
        assertEquals(4, capability.getOperations().size());
        server.verify();
    }

    private HttpPrivacyComputeProvider provider(RestTemplate client) {
        return new HttpPrivacyComputeProvider(ProviderType.KUSCIA_SECRETFLOW,
                "SecretFlow", "http://runtime", "token", false,
                Arrays.asList("SEMI_HONEST", "SEMI_HONEST_HE"),
                Arrays.asList("PSI_2P", "PSI_3P", "HE_PAILLIER", "VFL_SECUREBOOST"),
                client, new ObjectMapper());
    }

    private String health(String operations, String profiles) {
        return "{\"status\":\"UP\",\"version\":\"1.11.0b1\"," +
                "\"imageDigest\":\"sha256:" + repeat('a', 64) + "\"," +
                "\"controlPlaneOperations\":" + operations + "," +
                "\"securityProfiles\":" + profiles + "}";
    }

    private String repeat(char value, int count) {
        return String.join("", Collections.nCopies(count, String.valueOf(value)));
    }
}
