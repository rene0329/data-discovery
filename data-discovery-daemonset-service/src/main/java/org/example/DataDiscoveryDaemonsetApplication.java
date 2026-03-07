package org.example;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;  // 如果你的服务需要定时任务（如 @Scheduled），添加这个注解
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringBootApplication  // 启用 Spring Boot 自动配置和组件扫描
@Slf4j
//@EnableWebMvc
@EnableScheduling  // 可选：如果 FileDiscoveryService 有 @Scheduled 定时任务，启用调度
@MapperScan("org.example.mapper")
public class DataDiscoveryDaemonsetApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataDiscoveryDaemonsetApplication.class, args);
    }
}
