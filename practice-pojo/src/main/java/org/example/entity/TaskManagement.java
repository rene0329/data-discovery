package org.example.entity;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskManagement {

    private Integer taskId;

    private String taskName;
    private String selectedData;
    private String datasetIdsJson;
    private Long runtimeImageId;
    private String resourceOverridesJson;
    private String executionMode;
    private String acceptanceRunId;
    private Integer runRound;
    private String status;

    private LocalDateTime createTime;

    private Double T1;
    private Double T2;
    private Double rating;

    private String schedule;

    /** New semantic measurements. Legacy T1/T2/rating remain untouched for old tasks. */
    private Long dataPreparationMs;
    private Long computeDurationMs;
    private Boolean executionEvidenceComplete;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /** Read-only (调度结果 list): true when the task was launched by a 数据集管理 compute plan. */
    private Boolean manualSchedule;
}
