package org.example.dto.registration;

import java.util.Set;

public class DatasetDiscoveryRequest {
    private Set<Integer> nodeIds;

    public Set<Integer> getNodeIds() { return nodeIds; }
    public void setNodeIds(Set<Integer> nodeIds) { this.nodeIds = nodeIds; }
}
