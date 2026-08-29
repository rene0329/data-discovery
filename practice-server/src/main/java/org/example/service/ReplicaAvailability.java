package org.example.service;

public class ReplicaAvailability {
    private final String effectiveAvailability;
    private final boolean usable;
    private final String reason;

    public ReplicaAvailability(String effectiveAvailability, boolean usable, String reason) {
        this.effectiveAvailability = effectiveAvailability;
        this.usable = usable;
        this.reason = reason;
    }

    public String getEffectiveAvailability() { return effectiveAvailability; }
    public boolean isUsable() { return usable; }
    public String getReason() { return reason; }
}
