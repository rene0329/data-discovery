package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.mapper.DataManagementMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        double lambda = calculateLambda();
        int updatedRows = dataManagementMapper.updateAllDataHeat(alpha, countWeight, lambda, threshold);
        log.info("热度更新任务执行完毕，共原子更新 {} 条数据，lambda={}", updatedRows,
                String.format("%.4f", lambda));
    }

    /**
     * Lambda 计算方法
     * @return 计算出的lambda值
     */
    private double calculateLambda() {
        return k * time + lambda0 * Math.exp(-beta * time);
    }
}
