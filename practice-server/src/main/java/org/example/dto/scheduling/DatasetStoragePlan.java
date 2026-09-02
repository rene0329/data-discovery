package org.example.dto.scheduling;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class DatasetStoragePlan {
    private String mode;
    private int datasetCount;
    private List<SchedulingPlanRequest.Assignment> assignments = new ArrayList<>();
    private List<Placement> placements = new ArrayList<>();
    private List<String> notices = new ArrayList<>();

    @Data
    public static class Placement {
        private Long datasetId;
        private String datasetName;
        private Double dataHeat;
        private String sourceNode;
        private String targetNode;
        private String action;
    }

    @Data
    public static class Submit {
        private String mode;
        private String externalPlanId;
        private List<SchedulingPlanRequest.Assignment> assignments;
    }
}
