package org.example.service;

/** Resource requests of one processing Pod. CPU is in cores; memory and storage are in GiB. */
public final class JobResourceDemand {
    public static final double DEFAULT_CPU = 0.5;
    public static final double DEFAULT_MEMORY_GI = 1.0;
    public static final double DEFAULT_EPHEMERAL_STORAGE_GI = 2.0;
    private final double cpu;
    private final double memoryGi;
    private final double gpu;
    private final double ephemeralStorageGi;

    private JobResourceDemand(double cpu, double memoryGi, double gpu, double ephemeralStorageGi) {
        requirePositive(cpu, "CPU");
        requirePositive(memoryGi, "内存");
        if (!Double.isFinite(gpu) || gpu < 0 || gpu != Math.rint(gpu))
            throw new IllegalArgumentException("GPU 数量必须为非负整数");
        requirePositive(ephemeralStorageGi, "临时存储");
        this.cpu = cpu;
        this.memoryGi = memoryGi;
        this.gpu = gpu;
        this.ephemeralStorageGi = ephemeralStorageGi;
    }
    public static JobResourceDemand of(Double cpu, Double memoryGi) {
        return of(cpu, memoryGi, null);
    }
    public static JobResourceDemand of(Double cpu, Double memoryGi, Double gpu) {
        return of(cpu, memoryGi, gpu, DEFAULT_EPHEMERAL_STORAGE_GI);
    }
    public static JobResourceDemand of(Double cpu, Double memoryGi, Double gpu, Double ephemeralStorageGi) {
        return new JobResourceDemand(cpu == null ? DEFAULT_CPU : cpu,
                memoryGi == null ? DEFAULT_MEMORY_GI : memoryGi, gpu == null ? 0 : gpu,
                ephemeralStorageGi == null ? DEFAULT_EPHEMERAL_STORAGE_GI : ephemeralStorageGi);
    }
    private static void requirePositive(double value, String resource) {
        if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException(resource + "申请量必须为有限正数");
    }
    public double getCpu() { return cpu; }
    public double getMemoryGi() { return memoryGi; }
    public double getGpu() { return gpu; }
    public double getEphemeralStorageGi() { return ephemeralStorageGi; }
}
