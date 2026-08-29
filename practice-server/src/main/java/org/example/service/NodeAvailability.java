package org.example.service;

public class NodeAvailability {
    private final String effectiveStatus;
    private final boolean schedulable;
    private final String reason;

    public NodeAvailability(String effectiveStatus, boolean schedulable, String reason) {
        this.effectiveStatus = effectiveStatus;
        this.schedulable = schedulable;
        this.reason = reason;
    }

    public String getEffectiveStatus() { return effectiveStatus; }
    public boolean isSchedulable() { return schedulable; }
    public String getReason() { return reason; }
}
