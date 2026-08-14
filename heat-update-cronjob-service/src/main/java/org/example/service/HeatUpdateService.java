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

    @Value("${app.heat-update.half-life-hours:24}")
    private double halfLifeHours;

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
        int updatedRows = dataManagementMapper.decayAllDataHeat(halfLifeHours, threshold);
        log.info("热度衰减任务执行完毕，共更新 {} 条数据，halfLifeHours={}",
                updatedRows, halfLifeHours);
    }
}
