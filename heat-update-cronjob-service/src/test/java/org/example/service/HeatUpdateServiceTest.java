package org.example.service;

import org.example.mapper.DataManagementMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeatUpdateServiceTest {

    @Test
    void performHeatUpdateUsesConfiguredHalfLife() {
        DataManagementMapper mapper = mock(DataManagementMapper.class);
        when(mapper.decayAllDataHeat(24.0, 10.0)).thenReturn(5);

        HeatUpdateService service = new HeatUpdateService();
        ReflectionTestUtils.setField(service, "dataManagementMapper", mapper);
        ReflectionTestUtils.setField(service, "halfLifeHours", 24.0);
        ReflectionTestUtils.setField(service, "threshold", 10.0);

        service.performHeatUpdate();

        verify(mapper).decayAllDataHeat(eq(24.0), eq(10.0));
    }
}
