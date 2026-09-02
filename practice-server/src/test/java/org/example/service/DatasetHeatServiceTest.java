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
        heat.recordAccess(99L);
        verify(mapper).recordHeatAccess(99L, 24, 10, 0.9, 100);
        assertThrows(IllegalArgumentException.class, () -> new DatasetHeatService(mapper, 0, 10, 0.9, 100));
    }

    @Test
    void datasetViewPreservesHeatIncludingZeroInsteadOfInventingAValue() {
        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(9L).dataHeat(0.0).build();
        assertEquals(0.0, RegisteredDatasetView.from(dataset, Collections.emptyMap(), Collections.emptyList()).getDataHeat());
        dataset.setDataHeat(null);
        assertNull(RegisteredDatasetView.from(dataset, Collections.emptyMap(), Collections.emptyList()).getDataHeat());
    }
}
