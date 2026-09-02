package org.example.dto;

import lombok.Data;

/** Public egress IP observed by the host-network probe on this exact node. */
@Data
public class NodePublicIpDto {
    private String clusterId;
    private String nodeName;
    private String k8sUid;
    private String publicIp;
}
