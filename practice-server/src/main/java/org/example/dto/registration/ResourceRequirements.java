package org.example.dto.registration;

public class ResourceRequirements {
    private Double cpu;
    private Double memoryGi;
    private Double gpu;

    public Double getCpu() { return cpu; }
    public void setCpu(Double cpu) { this.cpu = cpu; }
    public Double getMemoryGi() { return memoryGi; }
    public void setMemoryGi(Double memoryGi) { this.memoryGi = memoryGi; }
    public Double getGpu() { return gpu; }
    public void setGpu(Double gpu) { this.gpu = gpu; }
}
