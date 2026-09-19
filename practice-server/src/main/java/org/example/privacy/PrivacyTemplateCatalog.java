package org.example.privacy;

import org.example.privacy.PrivacyComputeModels.CapabilityStatus;
import org.example.privacy.PrivacyComputeModels.ProviderCapability;
import org.example.privacy.PrivacyComputeModels.ProviderType;
import org.example.privacy.PrivacyComputeModels.TemplateDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PrivacyTemplateCatalog {
    private final PrivacyProviderRegistry providers;

    public PrivacyTemplateCatalog(PrivacyProviderRegistry providers) {
        this.providers = providers;
    }

    public List<TemplateDefinition> list() {
        List<TemplateDefinition> values = definitions();
        Map<ProviderType, ProviderCapability> capabilities = new LinkedHashMap<>();
        for (ProviderCapability item : providers.capabilities()) capabilities.put(item.getProvider(), item);
        for (TemplateDefinition value : values) {
            ProviderCapability capability = capabilities.get(value.getProvider());
            boolean operationSupported = capability != null
                    && capability.getOperations().contains(value.getOperation());
            boolean available = capability != null && capability.getStatus() == CapabilityStatus.AVAILABLE
                    && operationSupported;
            value.setAvailable(available);
            value.setImageDigest(capability == null ? null : capability.getImageDigest());
            if (!available) value.setUnavailableReason(capability == null
                    ? "provider is not registered"
                    : !operationSupported ? "provider does not expose this template operation"
                    : capability.getReason());
        }
        return values;
    }

    public TemplateDefinition find(String templateId) {
        if (templateId == null) return null;
        for (TemplateDefinition value : list()) {
            if (templateId.equals(value.getTemplateId())) return value;
        }
        return null;
    }

    private List<TemplateDefinition> definitions() {
        List<TemplateDefinition> values = new ArrayList<>();
        values.add(template("secure-sum-3p-v1", "三方恶意安全求和", ProviderType.MP_SPDZ,
                "SECURE_SUM", "MALICIOUS_3PC_HONEST_MAJORITY", roles("A", "PARTY", "B", "PARTY", "C", "PARTY"),
                Arrays.asList("sum"), 1800, "mp-spdz-0.4.3/malicious-rep-ring", false,
                "严格三方全部在线；不抵御同一 Kubernetes 集群管理员。"));
        values.add(template("private-stats-3p-v1", "三方恶意安全统计", ProviderType.MP_SPDZ,
                "PRIVATE_STATS", "MALICIOUS_3PC_HONEST_MAJORITY", roles("A", "PARTY", "B", "PARTY", "C", "PARTY"),
                Arrays.asList("sum", "count", "mean", "min", "max", "variance"), 1800,
                "mp-spdz-0.4.3/malicious-rep-ring", false,
                "仅发布配置的聚合统计；不提供掉线恢复。"));
        values.add(template("private-threshold-3p-v1", "三方恶意安全阈值判断", ProviderType.MP_SPDZ,
                "PRIVATE_THRESHOLD", "MALICIOUS_3PC_HONEST_MAJORITY", roles("A", "PARTY", "B", "PARTY", "C", "PARTY"),
                Collections.singletonList("boolean"), 1800, "mp-spdz-0.4.3/malicious-rep-ring", false,
                "仅允许预注册比较程序；不接受上传代码或任意命令。"));
        values.add(template("psi-2p-v1", "两方隐私集合求交", ProviderType.KUSCIA_SECRETFLOW,
                "PSI_2P", "SEMI_HONEST", roles("A", "RECEIVER", "B", "PROVIDER"),
                Arrays.asList("intersection", "intersection_count"), 1800, "psi-v0.6.0.dev260105/rr22", false,
                "半诚实 RR22；输出接收者获知交集。"));
        values.add(template("psi-3p-v1", "三方隐私集合求交", ProviderType.KUSCIA_SECRETFLOW,
                "PSI_3P", "SEMI_HONEST", roles("A", "PARTY", "B", "PARTY", "C", "PARTY"),
                Arrays.asList("intersection", "intersection_count"), 1800, "psi-v0.6.0.dev260105/ecdh-3pc", false,
                "半诚实 ECDH 3PC；接收方可能获知两方交集基数。"));
        values.add(template("pir-keyword-2p-v1", "两方关键词 PIR", ProviderType.KUSCIA_APSI,
                "PIR_KEYWORD", "SEMI_HONEST_PIR", roles("A", "CLIENT", "B", "SERVER"),
                Arrays.asList("hit", "value"), 1800, "psi-v0.6.0.dev260105/apsi", false,
                "仅关键词命中查询，不声明通用恶意安全 PIR。"));
        values.add(template("he-paillier-2p-v1", "两方 Paillier 同态计算", ProviderType.KUSCIA_SECRETFLOW,
                "HE_PAILLIER", "SEMI_HONEST_HE", roles("A", "KEY_HOLDER", "B", "DATA_HOLDER"),
                Arrays.asList("add", "plaintext_multiply", "dot_product"), 1800,
                "secretflow-1.11.0b1/heu-paillier", false,
                "支持加法同态及明文乘法，不声明全同态加密。"));
        values.add(template("hfl-fedavg-logreg-3p-v1", "三方横向联邦逻辑回归", ProviderType.KUSCIA_SFL,
                "HFL_FEDAVG_LOGREG", "SEMI_HONEST_FL", roles("A", "TRAINER", "B", "TRAINER", "C", "TRAINER"),
                Arrays.asList("model_reference", "metrics"), 3600, "sfl-c383e40f/fedavg-logreg", true,
                "实验组件；三方必须全部在线，不提供掉线恢复。"));
        values.add(template("vfl-secureboost-2p-v1", "两方纵向 SecureBoost", ProviderType.KUSCIA_SECRETFLOW,
                "VFL_SECUREBOOST", "SEMI_HONEST_HE", roles("A", "ACTIVE", "B", "PASSIVE"),
                Arrays.asList("model_reference", "metrics", "prediction_reference"), 3600,
                "secretflow-1.11.0b1/secureboost-heu", false,
                "先进行 PSI 对齐，再训练和预测；模型分片保留在所属方。"));
        return values;
    }

    private TemplateDefinition template(String id, String name, ProviderType provider, String operation,
                                          String security, Map<String, String> roles, List<String> results,
                                          int timeout, String protocol, boolean experimental, String leakage) {
        TemplateDefinition value = new TemplateDefinition();
        value.setTemplateId(id);
        value.setDisplayName(name);
        value.setProvider(provider);
        value.setOperation(operation);
        value.setSecurityProfile(security);
        value.setParticipantCount(roles.size());
        value.setRequiredRoles(roles);
        value.setSupportedResults(results);
        value.setMaxTimeoutSeconds(timeout);
        value.setProtocolVersion(protocol);
        value.setExperimental(experimental);
        value.setLeakageDisclosure(leakage);
        return value;
    }

    private Map<String, String> roles(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
        return result;
    }
}
