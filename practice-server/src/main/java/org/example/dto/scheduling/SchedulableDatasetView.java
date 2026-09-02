package org.example.dto.scheduling;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class SchedulableDatasetView {
    private Long datasetId;
    private String datasetCode;
    private String name;
    private String version;
    private String category;
    private String format;
    private Long sizeBytes;
    private Long sampleCount;
    private Map<String, Object> schemaSummary;
    private Map<String, Object> schedulingHints;
    private List<SchedulingReplicaView> replicas;
}
