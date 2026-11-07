package org.example.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cursor for overlapping Kubernetes timestamped log snapshots. */
final class ComputeLogCursor {
    private Instant timestamp;
    private int countAtTimestamp;

    List<String> newLines(String snapshot, boolean finished) {
        List<String> result = new ArrayList<>();
        if (snapshot == null || snapshot.isEmpty()) return result;
        Map<Instant, Integer> counts = new HashMap<>();
        String[] lines = snapshot.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty() || (!finished && index == lines.length - 1)) continue;
            int space = line.indexOf(' ');
            if (space <= 0) continue;
            Instant instant;
            try {
                // Kubernetes log timestamps may use the node's +08:00 offset.
                // Java 8 Instant.parse accepts Z only; normalize the offset first.
                instant = OffsetDateTime.parse(line.substring(0, space)).toInstant();
            } catch (RuntimeException invalidTimestamp) {
                continue;
            }
            int count = counts.merge(instant, 1, Integer::sum);
            if (timestamp != null && (instant.isBefore(timestamp)
                    || (instant.equals(timestamp) && count <= countAtTimestamp))) continue;
            timestamp = instant;
            countAtTimestamp = count;
            result.add(line.replace("\r", ""));
        }
        return result;
    }
}
