package org.example.service;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.factory.K8sJobFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class RuntimeImagePullVerifier {
    private final K8sJobFactory k8sJobFactory;
    private final String namespace;
    private final int timeoutSeconds;

    public RuntimeImagePullVerifier(K8sJobFactory k8sJobFactory,
                                    @Value("${dispatch.data-discovery.namespace:default}") String namespace,
                                    @Value("${app.runtime-image.verify-timeout-seconds:60}") int timeoutSeconds) {
        this.k8sJobFactory = k8sJobFactory;
        this.namespace = namespace;
        this.timeoutSeconds = Math.max(10, timeoutSeconds);
    }

    public VerificationResult verify(String imageRef, String pullSecretRef) {
        Map<String, KubernetesClient> clients = k8sJobFactory.getClusterClients();
        if (clients.isEmpty()) {
            throw new IllegalStateException("no Kubernetes cluster client is available");
        }
        String resolvedDigest = null;
        for (Map.Entry<String, KubernetesClient> entry : clients.entrySet()) {
            String digest = verifyInCluster(entry.getKey(), entry.getValue(), imageRef, pullSecretRef);
            if (resolvedDigest == null) resolvedDigest = digest;
            if (digest != null && resolvedDigest != null && !resolvedDigest.equals(digest)) {
                throw new IllegalStateException("image resolved to different digests across clusters");
            }
        }
        if (resolvedDigest == null || resolvedDigest.isEmpty()) {
            throw new IllegalStateException("image was pulled but no immutable digest was reported");
        }
        return new VerificationResult(resolvedDigest, "image pull verified in " + clients.size() + " cluster(s)");
    }

    private String verifyInCluster(String clusterId, KubernetesClient client,
                                   String imageRef, String pullSecretRef) {
        String podName = "image-verify-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<LocalObjectReference> pullSecrets = pullSecretRef == null || pullSecretRef.trim().isEmpty()
                ? Collections.emptyList()
                : Collections.singletonList(new LocalObjectReferenceBuilder().withName(pullSecretRef.trim()).build());
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .withNamespace(namespace)
                    .addToLabels("app.kubernetes.io/managed-by", "practice-server")
                    .addToLabels("topic4.io/purpose", "image-verification")
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .withImagePullSecrets(pullSecrets)
                    .addNewContainer()
                        .withName("verify")
                        .withImage(imageRef)
                        .withCommand("sh", "-c", "exit 0")
                    .endContainer()
                .endSpec()
                .build();
        try {
            client.pods().inNamespace(namespace).resource(pod).create();
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Pod current = client.pods().inNamespace(namespace).withName(podName).get();
                if (current != null && current.getStatus() != null
                        && current.getStatus().getContainerStatuses() != null) {
                    for (ContainerStatus status : current.getStatus().getContainerStatuses()) {
                        if (status.getImageID() != null && !status.getImageID().isEmpty()) {
                            return extractDigest(status.getImageID());
                        }
                        if (status.getState() != null && status.getState().getWaiting() != null) {
                            String reason = status.getState().getWaiting().getReason();
                            if ("ErrImagePull".equals(reason) || "ImagePullBackOff".equals(reason)
                                    || "InvalidImageName".equals(reason)) {
                                throw new IllegalStateException("cluster " + clusterId + " cannot pull image: "
                                        + status.getState().getWaiting().getMessage());
                            }
                        }
                    }
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("image verification interrupted", e);
                }
            }
            throw new IllegalStateException("cluster " + clusterId + " image pull verification timed out");
        } finally {
            client.pods().inNamespace(namespace).withName(podName).delete();
        }
    }

    private String extractDigest(String imageId) {
        int digestIndex = imageId.indexOf("sha256:");
        return digestIndex >= 0 ? imageId.substring(digestIndex) : imageId;
    }

    public static class VerificationResult {
        private final String digest;
        private final String message;

        public VerificationResult(String digest, String message) {
            this.digest = digest;
            this.message = message;
        }

        public String getDigest() { return digest; }
        public String getMessage() { return message; }
    }
}
