package org.example;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication  // 启用 Spring Boot 自动配置
@EnableScheduling       // 启用定时任务支持（因为 NetworkProbeService 有 @Scheduled）
public class NetworkProbeDaemonsetApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetworkProbeDaemonsetApplication.class, args);
    }

    @Bean
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();  // 默认配置，适用于 K8s 内部运行
    }

    // 新增：RestTemplate Bean
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();  // 简单创建，如果需要自定义（如超时），可以扩展
    }
}
