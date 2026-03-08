package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.job.HeatUpdateJobRunner;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
@MapperScan("org.example.mapper")
@Slf4j
public class HeatUpdateCronJobApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(HeatUpdateCronJobApplication.class, args);
        log.info("Heat Update CronJob Service started...");

        HeatUpdateJobRunner runner = context.getBean(HeatUpdateJobRunner.class);
        try {
            runner.run();
            log.info("Heat update completed successfully.");
            System.exit(0); // 成功退出
        } catch (Exception e) {
            log.error("Heat update failed: {}", e.getMessage(), e);
            System.exit(1); // 失败退出
        } finally {
            SpringApplication.exit(context); // 优雅关闭上下文
        }
    }
}