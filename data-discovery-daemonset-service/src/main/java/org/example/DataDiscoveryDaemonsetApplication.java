package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.config.Knife4jConfig;
import org.example.controller.DataDiscoveryController;
import org.example.controller.DatasetAccessController;
import org.example.daemon.DaemonComponentRoot;
import org.example.security.access.DatasetAccessScopeVerifier;
import org.example.security.access.NodeAccessTokenProperties;
import org.example.security.aggregation.worker.SecureAggregationWorkerController;
import org.example.security.aggregation.worker.SecureAggregationWorkerProperties;
import org.example.security.aggregation.worker.SecureAggregationWorkerService;
import org.example.service.DatasetReplicaAvailabilityService;
import org.example.service.FileDiscoveryService;
import org.example.service.NodeAvailabilityService;
import org.example.service.NodeDatasetReadService;
import org.example.service.NodeReadCacheService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;  // 如果你的服务需要定时任务（如 @Scheduled），添加这个注解

/**
 * Node agent application.
 *
 * The daemon depends on practice-server for shared mapper interfaces, but it
 * must never component-scan the control plane. Keep the automatic scan rooted
 * in an otherwise empty daemon package and explicitly import the small set of
 * node-owned components required at runtime.
 */
@SpringBootApplication(scanBasePackageClasses = DaemonComponentRoot.class)
@Import({
        Knife4jConfig.class,
        DataDiscoveryController.class,
        DatasetAccessController.class,
        FileDiscoveryService.class,
        NodeDatasetReadService.class,
        NodeReadCacheService.class,
        DatasetAccessScopeVerifier.class,
        NodeAccessTokenProperties.class,
        SecureAggregationWorkerController.class,
        SecureAggregationWorkerService.class,
        SecureAggregationWorkerProperties.class,
        DatasetReplicaAvailabilityService.class,
        NodeAvailabilityService.class
})
@Slf4j
@EnableScheduling  // 可选：如果 FileDiscoveryService 有 @Scheduled 定时任务，启用调度
@MapperScan("org.example.mapper")
public class DataDiscoveryDaemonsetApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataDiscoveryDaemonsetApplication.class, args);
    }
}
