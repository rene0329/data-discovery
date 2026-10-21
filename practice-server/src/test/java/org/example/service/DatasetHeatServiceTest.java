package org.example.service;

import org.example.dto.registration.RegisteredDatasetView;
import org.example.entity.RegisteredDataset;
import org.example.mapper.DatasetRegistrationMapper;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatasetHeatServiceTest {
    @Test
    void refreshAndAccessUseLogicalDatasetIdsAndConfiguredDecay() {
        DatasetRegistrationMapper mapper = mock(DatasetRegistrationMapper.class);
        DatasetHeatService heat = new DatasetHeatService(mapper, 24, 10, 0.9, 100);
        when(mapper.refreshHeat(24, 10)).thenReturn(8);
        assertEquals(8, heat.refresh());
        when(mapper.lockDataset(99L)).thenReturn(99L);
        RegisteredDataset before = RegisteredDataset.builder().datasetId(99L).status("ACTIVE").dataHeat(10.0).build();
        RegisteredDataset after = RegisteredDataset.builder().datasetId(99L).status("ACTIVE").dataHeat(19.0).build();
        when(mapper.findDatasetById(99L)).thenReturn(before, after);
        DatasetHeatService.HeatUpdate update = heat.recordAccess(99L);
        assertTrue(update.isUpdated());
        assertEquals(10.0, update.getBefore());
        assertEquals(19.0, update.getAfter());
        org.mockito.InOrder order = inOrder(mapper);
        order.verify(mapper).lockDataset(99L);
        order.verify(mapper).findDatasetById(99L);
        order.verify(mapper).recordHeatAccess(99L, 24, 10, 0.9, 100);
        order.verify(mapper).findDatasetById(99L);
        verify(mapper).recordHeatAccess(99L, 24, 10, 0.9, 100);
        assertThrows(IllegalArgumentException.class, () -> new DatasetHeatService(mapper, 0, 10, 0.9, 100));
    }

    @Test
    void missingOrInactiveDatasetIsNotReportedAsUpdated() {
        DatasetRegistrationMapper mapper = mock(DatasetRegistrationMapper.class);
        DatasetHeatService heat = new DatasetHeatService(mapper, 24, 10, 0.9, 100);
        assertFalse(heat.recordAccess(99L).isUpdated());
        when(mapper.lockDataset(99L)).thenReturn(99L);
        when(mapper.findDatasetById(99L)).thenReturn(RegisteredDataset.builder().status("DISABLED").build());
        assertFalse(heat.recordAccess(99L).isUpdated());
        verify(mapper, never()).recordHeatAccess(anyLong(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void datasetViewPreservesHeatIncludingZeroInsteadOfInventingAValue() {
        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(9L).dataHeat(0.0).build();
        assertEquals(0.0, RegisteredDatasetView.from(dataset, Collections.emptyMap(), Collections.emptyList()).getDataHeat());
        dataset.setDataHeat(null);
        assertNull(RegisteredDatasetView.from(dataset, Collections.emptyMap(), Collections.emptyList()).getDataHeat());
    }
}
