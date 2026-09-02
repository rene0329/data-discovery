package org.example.service;

import org.example.mapper.DatasetRegistrationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
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

    public void recordAccess(Long datasetId) {
        datasets.recordHeatAccess(datasetId, halfLifeHours, threshold, accessAlpha, maxHeat);
    }
}
