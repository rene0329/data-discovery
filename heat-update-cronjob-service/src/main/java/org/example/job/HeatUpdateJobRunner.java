package org.example.job;

import lombok.extern.slf4j.Slf4j;
import org.example.service.HeatUpdateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class HeatUpdateJobRunner {

    @Autowired
    private HeatUpdateService heatUpdateService;

    /**
     * 执行热度更新任务的主方法。
     * 在主应用类的 main 中调用这个方法。
     */
    public void run() {
        try {
            heatUpdateService.performHeatUpdate();
            log.info("热度更新任务执行完成。");
        } catch (Exception e) {
            log.error("热度更新任务执行失败: {}", e.getMessage(), e);
            throw e; // 抛出异常，让主类捕获并退出非0码
        }
    }
}
