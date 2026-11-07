package org.example.service;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores only input-read credentials in one administrator-provisioned Secret.
 * This service never creates or deletes Secrets and never exposes credentials in errors.
 */
@Service
public class FixedJobAccessSecretService {
    public static final String NAMESPACE = "default";
    public static final String SECRET_NAME = "topic4-compute-input-tokens";
    private static final int MAX_CONFLICT_ATTEMPTS = 5;

    public void putToken(KubernetesClient client, String jobName, String token) {
        requireJobName(jobName);
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("数据读取凭证不能为空");
        update(client, jobName, Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)), false);
    }

    public void removeToken(KubernetesClient client, String jobName) {
        requireJobName(jobName);
        update(client, jobName, null, true);
    }

    private void update(KubernetesClient client, String jobName, String encodedToken, boolean remove) {
        if (client == null) throw new IllegalArgumentException("目标集群客户端不能为空");
        for (int attempt = 0; attempt < MAX_CONFLICT_ATTEMPTS; attempt++) {
            try {
                Secret existing = client.secrets().inNamespace(NAMESPACE).withName(SECRET_NAME).get();
                if (existing == null || existing.getMetadata() == null
                        || !SECRET_NAME.equals(existing.getMetadata().getName())
                        || !NAMESPACE.equals(existing.getMetadata().getNamespace())
                        || existing.getMetadata().getResourceVersion() == null) {
                    throw new IllegalStateException("固定的数据读取凭证 Secret 缺失或版本无效，请由管理员初始化");
                }
                if (Boolean.TRUE.equals(existing.getImmutable()))
                    throw new IllegalStateException("固定的数据读取凭证 Secret 不允许更新，请由管理员检查配置");
                Map<String, String> data = existing.getData() == null ? new HashMap<>() : new HashMap<>(existing.getData());
                if (remove) {
                    if (data.remove(jobName) == null) return;
                } else {
                    if (encodedToken.equals(data.put(jobName, encodedToken))) return;
                }
                Secret updated = new SecretBuilder(existing).withData(data).withStringData(null).build();
                // Explicit optimistic concurrency: an update of another job's key forces a re-read.
                client.secrets().inNamespace(NAMESPACE).resource(updated)
                        .lockResourceVersion(existing.getMetadata().getResourceVersion()).replace();
                return;
            } catch (KubernetesClientException ex) {
                if (ex.getCode() == 409 && attempt + 1 < MAX_CONFLICT_ATTEMPTS) continue;
                // Do not propagate the request body/status: either may contain Secret data.
                throw new KubernetesClientException("无法更新固定的数据读取凭证 Secret", ex.getCode(), null);
            }
        }
    }

    private void requireJobName(String jobName) {
        // Controlled Job names are DNS labels and are also valid Secret data keys.
        if (jobName == null || jobName.length() > 63 || !jobName.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
            throw new IllegalArgumentException("计算作业名称不合法");
    }
}
