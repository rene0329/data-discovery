package org.example.service;

import java.time.Instant;
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
                instant = Instant.parse(line.substring(0, space));
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
