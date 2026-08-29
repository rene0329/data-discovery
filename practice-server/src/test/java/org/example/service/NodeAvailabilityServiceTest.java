package org.example.service;

import org.example.entity.NodeManagement;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeAvailabilityServiceTest {
    private final NodeAvailabilityService service = new NodeAvailabilityService(300);

    @Test
    void availableRequiresAdminAndRuntimeState() {
        NodeManagement node = NodeManagement.builder().registrationStatus("ACTIVE").enabled(true)
                .observedStatus("ONLINE").lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();

        NodeAvailability availability = service.evaluate(node);

        assertEquals("AVAILABLE", availability.getEffectiveStatus());
        assertTrue(availability.isSchedulable());
    }

    @Test
    void disabledPreservesObservedStateButPreventsScheduling() {
        NodeManagement node = NodeManagement.builder().registrationStatus("DISABLED").enabled(false)
                .observedStatus("ONLINE").lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();

        NodeAvailability availability = service.evaluate(node);

        assertEquals("DISABLED", availability.getEffectiveStatus());
        assertFalse(availability.isSchedulable());
    }

    @Test
    void notReadyNodeCannotBeScheduled() {
        NodeManagement node = NodeManagement.builder().registrationStatus("ACTIVE").enabled(true)
                .observedStatus("NOT_READY").observedStatusReason("KubeletNotReady")
                .lastSeenAt(LocalDateTime.now(ZoneOffset.UTC)).build();

        NodeAvailability availability = service.evaluate(node);

        assertEquals("NOT_READY", availability.getEffectiveStatus());
        assertEquals("KubeletNotReady", availability.getReason());
        assertFalse(availability.isSchedulable());
    }
}
