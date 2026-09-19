package org.example.security.aggregation.coordinator;

import java.time.LocalDateTime;

public class SecureAggregationMessageEvent {
    private Long eventId;
    private String runId;
    private String participantId;
    private String direction;
    private String messageType;
    private String status;
    private Integer payloadBytes;
    private String errorCode;
    private LocalDateTime createdAt;

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getParticipantId() { return participantId; }
    public void setParticipantId(String participantId) { this.participantId = participantId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getPayloadBytes() { return payloadBytes; }
    public void setPayloadBytes(Integer payloadBytes) { this.payloadBytes = payloadBytes; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
