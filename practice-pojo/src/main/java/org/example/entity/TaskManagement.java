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
    private String status;

    private LocalDateTime createTime;

    private Double T1;
    private Double T2;
    private Double rating;

    private String schedule;
}
