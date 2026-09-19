package org.example.access;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DatasetConsumerStat {
    private Integer consumerNodeId;
    private Long accessCount;
    private Long bytesRead;
    private LocalDateTime lastAccessAt;
}
