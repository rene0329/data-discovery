package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务线程池配置
 *
 * <p>专为 K8s Job 编排设计的阻塞型线程池。
 * 由于每个 processDataItem 内部会同步等待 K8s Job 完成（最长 10min × 2次），
 * 不能使用 ForkJoinPool.commonPool()，必须使用独立的、允许阻塞的线程池。
 */
@Configuration
public class AsyncConfig {

    /**
     * 用于 K8s 数据处理任务的专用线程池。
     * 注入名称为 "dataProcessingExecutor"，
     * 在 K8sTaskOrchestratorService 中通过 @Qualifier 引用。
     */
    @Bean("dataProcessingExecutor")
    public Executor dataProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 每个 dataItem 同时跑 2 个 Job（affinity + central），每个 Job 最多 10min
        // 核心线程数 = 预期并发数据项数；按业务量调整
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("k8s-data-proc-");
        executor.setKeepAliveSeconds(120);
        // 队列满时在调用方线程执行，避免丢任务
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
