package org.example.service;

import org.example.entity.NodeManagement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** A single scheduling snapshot; unknown capacity never means that a node fits. */
public final class NodeResourceLedger {
    private static final double EPSILON = 1e-9;
    private final Map<String, double[]> free = new HashMap<>();
    private final Map<String, double[]> capacity = new HashMap<>();
    private final Set<String> accountedJobs = new HashSet<>();

    /** Controlled Job names are globally unique and live in the default namespace. */
    public boolean accountsForJob(String jobName) { return accountedJobs.contains(jobName); }
    public void accountForJob(String jobName) { if (jobName != null) accountedJobs.add(jobName); }

    public static String key(NodeManagement node) {
        return node.getCluster() == null || node.getCluster().isEmpty() ? node.getNodeName()
                : node.getCluster() + "/" + node.getNodeName();
    }
    /** Compatibility helper for CPU/memory-only tests and consumers. */
    public void put(String nodeName, double cpu, double memoryGi) {
        put(nodeName, cpu, memoryGi, 0, Double.POSITIVE_INFINITY);
    }
    public void put(String key, double cpu, double memoryGi, double gpu, double ephemeralStorageGi) {
        free.put(key, new double[]{cpu, memoryGi, gpu, ephemeralStorageGi, Double.POSITIVE_INFINITY});
    }
    public void putCapacity(String key, double cpu, double memoryGi, double gpu, double ephemeralStorageGi) {
        capacity.put(key, new double[]{cpu, memoryGi, gpu, ephemeralStorageGi, Double.POSITIVE_INFINITY});
    }
    public void putPodSlots(String key, double total, double remaining) {
        if (capacity.containsKey(key)) capacity.get(key)[4] = total;
        if (free.containsKey(key)) free.get(key)[4] = remaining;
    }
    public boolean fits(NodeManagement node, JobResourceDemand demand) { return fits(key(node), demand); }
    public boolean fits(String key, JobResourceDemand demand) { return fits(free.get(key), demand); }
    public boolean canEverFit(NodeManagement node, JobResourceDemand demand) { return fits(capacity.get(key(node)), demand); }
    private boolean fits(double[] available, JobResourceDemand demand) {
        return available != null && available[0] + EPSILON >= demand.getCpu()
                && available[1] + EPSILON >= demand.getMemoryGi()
                && available[2] + EPSILON >= demand.getGpu()
                && available[3] + EPSILON >= demand.getEphemeralStorageGi()
                && available[4] >= 1;
    }
    public void reserve(NodeManagement node, JobResourceDemand demand) { reserve(key(node), demand); }
    public void reserve(String key, JobResourceDemand demand) {
        double[] available = free.get(key);
        if (available == null) return;
        available[0] -= demand.getCpu(); available[1] -= demand.getMemoryGi();
        available[2] -= demand.getGpu(); available[3] -= demand.getEphemeralStorageGi(); available[4] -= 1;
    }
    public double capacityCpu(String key) { return capacity.containsKey(key) ? capacity.get(key)[0] : 0; }
    public double capacityMemoryGi(String key) { return capacity.containsKey(key) ? capacity.get(key)[1] : 0; }
    public Double availableCpu(String key) { return available(key, 0); }
    public Double availableMemoryGi(String key) { return available(key, 1); }
    public Double availableGpu(String key) { return available(key, 2); }
    public Double availableEphemeralStorageGi(String key) { return available(key, 3); }
    private Double available(String key, int index) { return free.containsKey(key) ? free.get(key)[index] : null; }
    public String shortage(String key, JobResourceDemand demand) {
        double[] available = free.get(key);
        if (available == null) return key + " 资源信息暂不可用";
        return key + " 资源不足（需要 " + format(demand.getCpu()) + " CPU / " + format(demand.getMemoryGi())
                + " GiB 内存 / " + format(demand.getGpu()) + " GPU / " + format(demand.getEphemeralStorageGi())
                + " GiB 临时存储，空闲 " + format(Math.max(0, available[0])) + " CPU / "
                + format(Math.max(0, available[1])) + " GiB 内存 / " + format(Math.max(0, available[2]))
                + " GPU / " + format(Math.max(0, available[3])) + " GiB 临时存储）";
    }
    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }
}
