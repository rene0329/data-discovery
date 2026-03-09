package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.DataManagement;
import org.example.mapper.DataManagementMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 核心业务服务：负责计算和更新数据的热度值。
 * 这里的逻辑是从旧的 UpdateHeat 类中迁移过来的。
 */
@Service
@Slf4j
public class HeatUpdateService {

    // 从配置文件读取参数，便于调整
    @Value("${app.heat-update.alpha:0.99}")
    private double alpha;

    /** 每次访问对热度的直接增量权重；与 alpha 解耦，使 count=1 时热度显著上升 */
    @Value("${app.heat-update.count-weight:5.0}")
    private double countWeight;

    @Value("${app.heat-update.k:0.05}")
    private double k;

    @Value("${app.heat-update.lambda0:0.05}")
    private double lambda0;

    @Value("${app.heat-update.beta:0.3}")
    private double beta;

    @Value("${app.heat-update.time:10}")
    private double time;

    @Value("${app.heat-update.threshold:10}")
    private double threshold;

    @Autowired
    private DataManagementMapper dataManagementMapper;

    /**
     * 执行一次完整的热度更新计算。
     * 这个方法会被 HeatUpdateJobRunner 调用。
     */
    @Transactional // 添加事务，确保更新原子性
    public void performHeatUpdate() {
        log.info("开始执行热度更新任务...");

        List<DataManagement> allData = dataManagementMapper.getAllData();
        if (allData == null || allData.isEmpty()) {
            log.warn("数据库中没有找到任何数据，任务结束。");
            return;
        }

        int successCount = 0;
        int failureCount = 0;

        for (DataManagement data : allData) {
            try {
                // 获取旧的热度和本轮增量（count 每轮结束后清零，所以 count = 本轮新增调用次数）
                double oldHeat = Double.parseDouble(String.valueOf(data.getDataHeat()));
                int count = data.getDataCount();

                // 计算 lambda
                double lambda = calculateLambda();

                // 计算新的热度，确保不低于阈值
                // countWeight 为每次访问的直接热度增量，与 EMA 平滑系数 alpha 解耦
                // count=1 时净增量 ≈ countWeight - lambda - (1-alpha)*oldHeat
                double newHeat = Math.max(threshold, alpha * oldHeat + countWeight * count - lambda);
                log.debug("{}  oldHeat={} delta_count={} newHeat={}", data.getDataName(), String.format("%.2f", oldHeat), count, String.format("%.2f", newHeat));

                // 更新热度同时清零 count，count 重新累积到下一轮
                dataManagementMapper.updateDataHeat(data.getDataName(), newHeat);
                successCount++;

            } catch (NumberFormatException e) {
                log.error("解析数据热度时出错，数据名: {}, 热度值: '{}'", data.getDataName(), data.getDataHeat(), e);
                failureCount++;
            } catch (Exception e) {
                log.error("更新数据时发生未知错误，数据名: {}", data.getDataName(), e);
                failureCount++;
            }
        }
        log.info("热度更新任务执行完毕，共处理 {} 条数据，成功 {} 条，失败 {} 条。", allData.size(), successCount, failureCount);
    }

    /**
     * Lambda 计算方法
     * @return 计算出的lambda值
     */
    private double calculateLambda() {
        return k * time + lambda0 * Math.exp(-beta * time);
    }
}
