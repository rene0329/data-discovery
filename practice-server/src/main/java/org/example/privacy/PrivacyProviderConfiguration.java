package org.example.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.privacy.PrivacyComputeModels.CapabilityStatus;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Collections;

@Configuration(proxyBeanMethods = false)
public class PrivacyProviderConfiguration {
    @Bean
    PrivacyComputeProvider mpSpdzPrivacyProvider(Environment env, ObjectMapper mapper) {
        return http(ProviderType.MP_SPDZ, "MP-SPDZ", "mp-spdz", false,
                Collections.singletonList("MALICIOUS_3PC_HONEST_MAJORITY"),
                Arrays.asList("SECURE_SUM", "PRIVATE_STATS", "PRIVATE_THRESHOLD"), env, mapper);
    }

    @Bean
    PrivacyComputeProvider secretFlowPrivacyProvider(Environment env, ObjectMapper mapper) {
        return http(ProviderType.KUSCIA_SECRETFLOW, "Kuscia / SecretFlow", "secretflow", false,
                Arrays.asList("SEMI_HONEST", "SEMI_HONEST_HE"),
                Arrays.asList("PSI_2P", "PSI_3P"), env, mapper);
    }

    @Bean
    PrivacyComputeProvider apsiPrivacyProvider(Environment env, ObjectMapper mapper) {
        return http(ProviderType.KUSCIA_APSI, "Kuscia / APSI", "apsi", false,
                Collections.singletonList("SEMI_HONEST_PIR"),
                Collections.singletonList("PIR_KEYWORD"), env, mapper);
    }

    @Bean
    PrivacyComputeProvider sflPrivacyProvider(Environment env, ObjectMapper mapper) {
        return http(ProviderType.KUSCIA_SFL, "Kuscia / SFL", "sfl", true,
                Collections.singletonList("SEMI_HONEST_FL"),
                Collections.singletonList("HFL_FEDAVG_LOGREG"), env, mapper);
    }

    @Bean
    PrivacyComputeProvider flowerReservedPrivacyProvider() {
        return new UnavailablePrivacyComputeProvider(ProviderType.FLOWER_RESERVED,
                "Flower SecAgg+ (reserved)", CapabilityStatus.RESERVED,
                "dropout-tolerant federated aggregation is reserved for a later release");
    }

    private PrivacyComputeProvider http(ProviderType type, String name, String key, boolean experimental,
                                        java.util.List<String> profiles, java.util.List<String> operations,
                                        Environment env, ObjectMapper mapper) {
        String prefix = "privacy-computing.providers." + key;
        String baseUrl = env.getProperty(prefix + ".base-url", "");
        String token = env.getProperty(prefix + ".bearer-token", "");
        int connect = env.getProperty("privacy-computing.http.connect-timeout-ms", Integer.class, 1500);
        int read = env.getProperty("privacy-computing.http.read-timeout-ms", Integer.class, 5000);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(250, connect));
        factory.setReadTimeout(Math.max(500, read));
        return new HttpPrivacyComputeProvider(type, name, baseUrl, token, experimental,
                profiles, operations, new RestTemplate(factory), mapper);
    }
}
