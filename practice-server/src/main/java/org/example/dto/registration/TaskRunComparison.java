package org.example.dto.registration;

public class TaskRunComparison {
    private final String acceptanceRunId;
    private final Integer runRound;
    private final Integer centralizedTaskId;
    private final Integer inPlaceTaskId;
    private final Long centralizedPreparationMs;
    private final Long inPlacePreparationMs;
    private final Double centralizedToInPlaceRatio;
    private final boolean comparable;
    private final String reason;

    public TaskRunComparison(String acceptanceRunId, Integer runRound,
                             Integer centralizedTaskId, Integer inPlaceTaskId,
                             Long centralizedPreparationMs, Long inPlacePreparationMs,
                             Double centralizedToInPlaceRatio, boolean comparable, String reason) {
        this.acceptanceRunId = acceptanceRunId;
        this.runRound = runRound;
        this.centralizedTaskId = centralizedTaskId;
        this.inPlaceTaskId = inPlaceTaskId;
        this.centralizedPreparationMs = centralizedPreparationMs;
        this.inPlacePreparationMs = inPlacePreparationMs;
        this.centralizedToInPlaceRatio = centralizedToInPlaceRatio;
        this.comparable = comparable;
        this.reason = reason;
    }

    public String getAcceptanceRunId() { return acceptanceRunId; }
    public Integer getRunRound() { return runRound; }
    public Integer getCentralizedTaskId() { return centralizedTaskId; }
    public Integer getInPlaceTaskId() { return inPlaceTaskId; }
    public Long getCentralizedPreparationMs() { return centralizedPreparationMs; }
    public Long getInPlacePreparationMs() { return inPlacePreparationMs; }
    public Double getCentralizedToInPlaceRatio() { return centralizedToInPlaceRatio; }
    public boolean isComparable() { return comparable; }
    public String getReason() { return reason; }
}
