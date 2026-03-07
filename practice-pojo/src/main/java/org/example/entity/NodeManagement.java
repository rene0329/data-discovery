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
public class NodeManagement {
    private Integer nodeId;
    private String nodeName;


    private String externalIp;
    private String internalIp;
    private String type;
    private String cluster;

    private Double maxCpu;
    private Double maxMemory;

    private Double currentCpu;
    private Double currentMemory;

    private Integer numDataset;
    private LocalDateTime lastUpdateTime;

}


