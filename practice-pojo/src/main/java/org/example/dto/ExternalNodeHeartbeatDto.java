package org.example.dto;

import lombok.Data;

/** Reachability observation for a registered node outside the local Kubernetes cluster. */
@Data
public class ExternalNodeHeartbeatDto {
    private String clusterId;
    private String nodeName;
    private String internalIp;
    private Boolean online;
    private String reason;
}
