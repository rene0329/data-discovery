package org.example.dto.scheduling;

import lombok.Data;

import java.util.List;

@Data
public class SchedulingPlanRequest {
    private String externalPlanId;
    private String taskId;
    private Algorithm algorithm;
    private List<Assignment> assignments;

    @Data
    public static class Algorithm {
        private String name;
        private String version;
    }

    @Data
    public static class Assignment {
        private Long datasetId;
        private Long replicaId;
        private Integer sourceNodeId;
        private Integer targetNodeId;
        private String action;
    }
}
