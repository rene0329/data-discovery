package org.example.dto.scheduling;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SchedulingReplicaView {
    private Long replicaId;
    private Integer nodeId;
    private String nodeName;
    private String filePath;
    private Long sizeBytes;
    private String availability;
}
