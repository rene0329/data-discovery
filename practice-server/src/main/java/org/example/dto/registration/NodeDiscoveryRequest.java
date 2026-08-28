package org.example.dto.registration;

import java.util.Set;

public class NodeDiscoveryRequest {
    private Set<String> clusterIds;

    public Set<String> getClusterIds() { return clusterIds; }
    public void setClusterIds(Set<String> clusterIds) { this.clusterIds = clusterIds; }
}
