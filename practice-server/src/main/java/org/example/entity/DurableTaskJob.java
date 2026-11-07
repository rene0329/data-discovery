package org.example.entity;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;

/** Durable identity of one dataset/mode execution. No bearer credentials are stored here. */
@Data
public class DurableTaskJob {
    private Long id;
    private String jobKey;
    private Integer taskId;
    private Long datasetId;
    private String executionMode;
    private Long planId;
    private Long assignmentId;
    private Long runtimeImageId;
    @JsonIgnore private String overridesJson;
    private Long replicaId;
    private String targetNode;
    private String clusterId;
    private String comparisonNode;
    private Double cpu;
    private Double memoryGi;
    private Double gpu;
    private Double ephemeralStorageGi;
    private String state;
    private String waitingReason;
    private String jobName;
    private String jobUid;
    private Long migrationId;
    private Integer attempt;
    private Boolean externalPrepared;
    @JsonIgnore private String resultJson;
    private LocalDateTime queuedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime boundAt;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime finishedAt;
    private LocalDateTime cleanedAt;
    private LocalDateTime credentialRefreshedAt;
    public boolean terminal() {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }
}
