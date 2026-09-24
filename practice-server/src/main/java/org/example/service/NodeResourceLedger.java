package org.example.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Free CPU and memory per node for one placement pass. Placing a Job reserves its request, so the
 * next dataset of the same task sees what the earlier ones took. Nodes without data always fit.
 */
public final class NodeResourceLedger {
    private static final double EPSILON = 1e-9;
    private final Map<String, double[]> free = new HashMap<>();

    public void put(String nodeName, double cpu, double memoryGi) {
        free.put(nodeName, new double[]{cpu, memoryGi});
    }

    public boolean fits(String nodeName, JobResourceDemand demand) {
        double[] available = free.get(nodeName);
        return available == null || (available[0] + EPSILON >= demand.getCpu()
                && available[1] + EPSILON >= demand.getMemoryGi());
    }

    public void reserve(String nodeName, JobResourceDemand demand) {
        double[] available = free.get(nodeName);
        if (available == null) return;
        available[0] -= demand.getCpu();
        available[1] -= demand.getMemoryGi();
    }

    /** e.g. "cluster-sz-1 资源不足（需要 2 CPU / 1 GiB，空闲 1.88 CPU / 7.18 GiB）" */
    public String shortage(String nodeName, JobResourceDemand demand) {
        double[] available = free.get(nodeName);
        return nodeName + " 资源不足（需要 " + format(demand.getCpu()) + " CPU / " + format(demand.getMemoryGi())
                + " GiB，空闲 " + format(Math.max(0, available[0])) + " CPU / " + format(Math.max(0, available[1])) + " GiB）";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }
}
