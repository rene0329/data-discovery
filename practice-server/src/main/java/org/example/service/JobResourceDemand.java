package org.example.service;

/** CPU and memory one task Job requests; K8sJobFactory puts the same values on the processing container. */
public final class JobResourceDemand {
    public static final double DEFAULT_CPU = 0.5;
    public static final double DEFAULT_MEMORY_GI = 1.0;

    private final double cpu;
    private final double memoryGi;

    private JobResourceDemand(double cpu, double memoryGi) {
        this.cpu = cpu;
        this.memoryGi = memoryGi;
    }

    /** Unset values fall back to the factory defaults. */
    public static JobResourceDemand of(Double cpu, Double memoryGi) {
        return new JobResourceDemand(cpu != null ? cpu : DEFAULT_CPU, memoryGi != null ? memoryGi : DEFAULT_MEMORY_GI);
    }

    public double getCpu() { return cpu; }
    public double getMemoryGi() { return memoryGi; }
}
