package org.example.service;

import org.example.mapper.DataManagementMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeatUpdateServiceTest {

    @Test
    void performHeatUpdateConsumesCountsWithOneAtomicMapperUpdate() {
        DataManagementMapper mapper = mock(DataManagementMapper.class);
        when(mapper.updateAllDataHeat(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(5);

        HeatUpdateService service = new HeatUpdateService();
        ReflectionTestUtils.setField(service, "dataManagementMapper", mapper);
        ReflectionTestUtils.setField(service, "alpha", 0.85);
        ReflectionTestUtils.setField(service, "countWeight", 5.0);
        ReflectionTestUtils.setField(service, "k", 0.009);
        ReflectionTestUtils.setField(service, "lambda0", 0.05);
        ReflectionTestUtils.setField(service, "beta", 0.3);
        ReflectionTestUtils.setField(service, "time", 10.0);
        ReflectionTestUtils.setField(service, "threshold", 10.0);

        service.performHeatUpdate();

        double expectedLambda = 0.009 * 10.0 + 0.05 * Math.exp(-0.3 * 10.0);
        verify(mapper).updateAllDataHeat(eq(0.85), eq(5.0), eq(expectedLambda), eq(10.0));
    }
}
