package org.example.service;

import org.example.mapper.DatasetRegistrationMapper;
import org.example.entity.RegisteredDataset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class DatasetHeatService {
    private final DatasetRegistrationMapper datasets;
    private final double halfLifeHours, threshold, accessAlpha, maxHeat;

    public DatasetHeatService(DatasetRegistrationMapper datasets,
            @Value("${app.heat-update.half-life-hours:24}") double halfLifeHours,
            @Value("${app.heat-update.threshold:10}") double threshold,
            @Value("${app.heat-update.access-alpha:0.90}") double accessAlpha,
            @Value("${app.heat-update.max-heat:100}") double maxHeat) {
        if (!Double.isFinite(halfLifeHours) || halfLifeHours <= 0 || !Double.isFinite(threshold)
                || threshold < 0 || !Double.isFinite(maxHeat) || maxHeat < threshold
                || !Double.isFinite(accessAlpha) || accessAlpha < 0 || accessAlpha > 1) {
            throw new IllegalArgumentException("invalid dataset heat configuration");
        }
        this.datasets = datasets;
        this.halfLifeHours = halfLifeHours;
        this.threshold = threshold;
        this.accessAlpha = accessAlpha;
        this.maxHeat = maxHeat;
    }

    @Transactional
    public int refresh() { return datasets.refreshHeat(halfLifeHours, threshold); }

    @Transactional
    public HeatUpdate recordAccess(Long datasetId) {
        if (datasets.lockDataset(datasetId) == null) {
            return new HeatUpdate(false, null, null);
        }
        RegisteredDataset before = datasets.findDatasetById(datasetId);
        if (before == null || !"ACTIVE".equals(before.getStatus())) {
            return new HeatUpdate(false, null, null);
        }
        datasets.recordHeatAccess(datasetId, halfLifeHours, threshold, accessAlpha, maxHeat);
        RegisteredDataset after = datasets.findDatasetById(datasetId);
        if (after == null) throw new IllegalStateException("dataset disappeared during heat update");
        return new HeatUpdate(true, before.getDataHeat(), after.getDataHeat());
    }

    public static void logAccessUpdate(Long datasetId, Integer taskId, String requestId, HeatUpdate update) {
        if (update == null) return;
        if (!update.updated) {
            log.warn("【热度】未更新 datasetId={} taskId={} requestId={} 原因=数据集不存在或未启用",
                    datasetId, taskId, requestId);
            return;
        }
        log.info("【热度】数据使用后更新成功 datasetId={} taskId={} requestId={} 更新前={} → 更新后={}",
                datasetId, taskId, requestId, update.before, update.after);
    }

    public static class HeatUpdate {
        private final boolean updated;
        private final Double before;
        private final Double after;
        HeatUpdate(boolean updated, Double before, Double after) {
            this.updated = updated;
            this.before = before;
            this.after = after;
        }
        public boolean isUpdated() { return updated; }
        public Double getBefore() { return before; }
        public Double getAfter() { return after; }
    }
}
