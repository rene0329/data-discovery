package org.example.service;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ComputeLogCursorTest {
    @Test
    void acceptsNodeTimezoneOffsetsAndNormalizesTheCursorToUtc() {
        ComputeLogCursor cursor = new ComputeLogCursor();
        String line = "2026-11-07T09:00:00.123456789+08:00 Epoch 1/1\n";
        assertEquals(Collections.singletonList(line.trim()), cursor.newLines(line, false));
        assertTrue(cursor.newLines("2026-11-07T01:00:00.123456789Z Epoch 1/1\n", false).isEmpty());
        assertEquals(1, cursor.newLines("2026-11-07T09:00:01+08:00 loss=0.42\n", false).size());
    }
    @Test
    void overlappingSnapshotsDoNotRepeatLinesButKeepRepeatedProgressAtNewTimes() {
        ComputeLogCursor cursor = new ComputeLogCursor();
        String first = "2026-11-07T01:00:00.123456789Z training\n";
        String second = "2026-11-07T01:00:05Z training\n";
        assertEquals(Collections.singletonList(first.trim()), cursor.newLines(first, false));
        assertTrue(cursor.newLines(first, false).isEmpty());
        assertEquals(Collections.singletonList(second.trim()), cursor.newLines(first + second, false));
        assertTrue(cursor.newLines(second, false).isEmpty());
    }

    @Test
    void preservesMultipleMessagesSharingTheSameTimestamp() {
        ComputeLogCursor cursor = new ComputeLogCursor();
        String prefix = "2026-11-07T01:00:00Z ";
        assertEquals(Arrays.asList(prefix + "epoch", prefix + "loss"),
                cursor.newLines(prefix + "epoch\n" + prefix + "loss\n", false));
        assertEquals(Collections.singletonList(prefix + "accuracy"),
                cursor.newLines(prefix + "epoch\n" + prefix + "loss\n" + prefix + "accuracy\n", false));
    }

    @Test
    void waitsForCompleteLinesAndFlushesFinalUnterminatedOutput() {
        ComputeLogCursor cursor = new ComputeLogCursor();
        assertTrue(cursor.newLines("2026-11-07T01:00:00Z epoch=", false).isEmpty());
        assertEquals(Collections.singletonList("2026-11-07T01:00:00Z epoch=1"),
                cursor.newLines("2026-11-07T01:00:00Z epoch=1\n", false));
        assertEquals(Collections.singletonList("2026-11-07T01:00:01Z completed"),
                cursor.newLines("2026-11-07T01:00:01Z completed", true));
    }

    @Test
    void ignoresMalformedRecordsAndOlderTailWindows() {
        ComputeLogCursor cursor = new ComputeLogCursor();
        assertTrue(cursor.newLines(null, false).isEmpty());
        assertTrue(cursor.newLines("not a timestamp\n", true).isEmpty());
        cursor.newLines("2026-11-07T01:00:05Z last\n", false);
        assertTrue(cursor.newLines("2026-11-07T01:00:01Z old\n", false).isEmpty());
    }
}
