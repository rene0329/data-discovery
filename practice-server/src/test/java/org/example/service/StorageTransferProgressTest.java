package org.example.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.entity.SchedulingAssignment;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class StorageTransferProgressTest {
    @Test
    void closingStopsProgressOnBothNormalAndExceptionalExit() {
        Logger logger = (Logger) LoggerFactory.getLogger(StorageTransferProgress.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            for (boolean fail : new boolean[]{false, true}) {
                StorageTransferProgress progress = new StorageTransferProgress(SchedulingAssignment.builder()
                        .planId(40L).assignmentId(50L).datasetId(10L).build());
                try (StorageTransferProgress ignored = progress) {
                    progress.tick();
                    if (fail) throw new IllegalStateException("test failure");
                } catch (IllegalStateException expected) { assertTrue(fail); }
                int count = events.list.size();
                progress.tick();
                assertEquals(count, events.list.size());
            }
            assertEquals(2, events.list.size());
            String message = events.list.get(0).getFormattedMessage();
            assertTrue(message.contains("planId=40 assignmentId=50 datasetId=10"));
            assertTrue(message.contains("已等待="));
            assertFalse(message.contains("%"));
        } finally { logger.detachAppender(events); }
    }
}
