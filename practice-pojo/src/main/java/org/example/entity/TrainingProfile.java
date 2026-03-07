package org.example.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingProfile {
    private Integer id;
    private String profileId;
    private String taskType;
    private String modelType;
    private String image;
    private String entrypoint;
    private String dataPathTemplate;
    private Double defaultCpu;
    private Double defaultMem;
    private Integer active;
}
