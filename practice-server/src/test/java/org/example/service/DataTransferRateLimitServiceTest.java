package org.example.service;

import org.example.auth.AuthenticatedUser;
import org.example.auth.CurrentUserService;
import org.example.dto.admin.DataTransferRateLimitDtos;
import org.example.entity.DataTransferRateLimitConfiguration;
import org.example.mapper.DataTransferRateLimitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataTransferRateLimitServiceTest {
    private DataTransferRateLimitMapper mapper;
    private CurrentUserService currentUsers;
    private DataTransferRateLimitService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DataTransferRateLimitMapper.class);
        currentUsers = mock(CurrentUserService.class);
        when(currentUsers.currentUser()).thenReturn(new AuthenticatedUser(1L, "admin", "Admin",
                Collections.singleton("ADMIN"), null, null, null));
        service = new DataTransferRateLimitService(mapper, currentUsers, null,
                "*->master-40=960k,*->master-215=1392k");
    }

    @Test
    void resolvesEveryNewTransferFromTheCurrentDatabaseVersion() {
        when(mapper.findCurrent()).thenReturn(configuration(7L, true,
                "cluster-sh-2->*=371k,*->master-40=960k,*->master-215=1392k"));

        DataTransferRateLimitService.Resolution special = service.resolve("cluster-sh-2", "master-215");
        DataTransferRateLimitService.Resolution central = service.resolve("cluster-sz-3", "master-40");

        assertEquals("371k", special.getRate());
        assertEquals("960k", central.getRate());
        assertEquals(7L, central.getVersion());
        assertEquals("database", central.getSource());
    }

    @Test
    void disabledDatabaseConfigurationMeansUnlimitedRatherThanEnvironmentFallback() {
        when(mapper.findCurrent()).thenReturn(configuration(8L, false, "*->master-40=960k"));

        DataTransferRateLimitService.Resolution resolution = service.resolve("node-a", "master-40");

        assertNull(resolution.getRate());
        assertEquals(8L, resolution.getVersion());
        assertEquals("database", resolution.getSource());
    }

    @Test
    void fallsBackToDeploymentRulesWhenTheDatabaseCannotBeRead() {
        when(mapper.findCurrent()).thenThrow(new IllegalStateException("database unavailable"));

        DataTransferRateLimitService.Resolution resolution = service.resolve("node-a", "master-215");

        assertEquals("1392k", resolution.getRate());
        assertEquals(0L, resolution.getVersion());
        assertEquals("deployment-fallback", resolution.getSource());
    }

    @Test
    void updateUsesOptimisticVersioningAndWritesHistory() {
        DataTransferRateLimitConfiguration before = configuration(3L, true, "*->master-40=960k");
        DataTransferRateLimitConfiguration after = configuration(4L, true,
                "*->master-40=900k,*->master-215=1305k");
        when(mapper.findCurrent()).thenReturn(before, after);
        when(mapper.update(eq(3L), eq(4L), anyBoolean(), any(), anyString(),
                any(BigDecimal.class), any(BigDecimal.class), eq("admin"))).thenReturn(1);

        DataTransferRateLimitDtos.UpdateRequest request = new DataTransferRateLimitDtos.UpdateRequest();
        request.setVersion(3L);
        request.setEnabled(true);
        request.setDefaultRate("");
        request.setPathRules("*->master-40=900k,*->master-215=1305k");
        request.setTargetMin(new BigDecimal("1.400"));
        request.setTargetMax(new BigDecimal("1.500"));

        DataTransferRateLimitDtos.View result = service.update(request);

        assertEquals(4L, result.getVersion());
        verify(mapper).insertCurrentHistory();
        verify(mapper).update(eq(3L), eq(4L), eq(true), eq(null),
                eq("*->master-40=900k,*->master-215=1305k"),
                eq(new BigDecimal("1.400")), eq(new BigDecimal("1.500")), eq("admin"));
    }

    private DataTransferRateLimitConfiguration configuration(Long version, boolean enabled, String rules) {
        DataTransferRateLimitConfiguration value = new DataTransferRateLimitConfiguration();
        value.setConfigId(1);
        value.setVersion(version);
        value.setEnabled(enabled);
        value.setDefaultRate(null);
        value.setPathRules(rules);
        value.setTargetMin(new BigDecimal("1.400"));
        value.setTargetMax(new BigDecimal("1.500"));
        value.setUpdatedBy("admin");
        return value;
    }
}
