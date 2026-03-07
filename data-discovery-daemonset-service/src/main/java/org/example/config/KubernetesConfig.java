package org.example.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value; // 如果不再需要注入kubeconfigPath，可以移除此行
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.io.File; // 可以移除此行
import java.nio.file.Files; // 可以移除此行
import java.nio.file.Path;  // 可以移除此行
import java.nio.file.Paths; // 可以移除此行


@Configuration
public class KubernetesConfig {

    private static final Logger log = LoggerFactory.getLogger(KubernetesConfig.class);

    // 移除 kubeConfigPath 的 @Value 注入，因为我们不再手动读取文件
    // @Value("${kubernetes.config.path:~/.kube/config}")
    // private String kubeConfigPath;

    @Bean
    public KubernetesClient kubernetesClient() {
        log.info("尝试使用 Fabric8 Kubernetes Client 自动配置模式...");
        // Fabric8 按优先级自动查找配置：
        // 1. KUBECONFIG 环境变量  2. In-cluster ServiceAccount  3. ~/.kube/config
        KubernetesClient client = new KubernetesClientBuilder().build();
        log.info("KubernetesClient 初始化成功，API Server: {}", client.getMasterUrl());
        // 节点列表仅用于启动日志验证，无权限时跳过（DaemonSet 可不配置 RBAC）
        try {
            client.nodes().list().getItems().forEach(node -> log.info("  - 发现节点: {}", node.getMetadata().getName()));
        } catch (Exception e) {
            log.warn("无法列出集群节点（ServiceAccount 可能无 nodes/list 权限），跳过验证: {}", e.getMessage());
        }
        return client;
    }

    // 假设你的 RestTemplate 还在这个配置类里
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
