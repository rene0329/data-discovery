package org.example.dto.registration;

import org.example.entity.NodeDiscoveryCandidate;

import java.time.LocalDateTime;
import java.util.Map;

public class NodeCandidateView {
    private Long candidateId;
    private String clusterId;
    private String k8sUid;
    private String k8sNodeName;
    private String internalIp;
    private String externalIp;
    private String observedRole;
    private Double maxCpu;
    private Double maxMemoryGi;
    private String observedStatus;
    private Integer registeredNodeId;
    private Map<String, String> labels;
    private LocalDateTime lastSeenAt;

    public static NodeCandidateView from(NodeDiscoveryCandidate entity, Map<String, String> labels) {
        NodeCandidateView view = new NodeCandidateView();
        view.candidateId = entity.getCandidateId();
        view.clusterId = entity.getClusterId();
        view.k8sUid = entity.getK8sUid();
        view.k8sNodeName = entity.getK8sNodeName();
        view.internalIp = entity.getInternalIp();
        view.externalIp = entity.getExternalIp();
        view.observedRole = entity.getObservedRole();
        view.maxCpu = entity.getMaxCpu();
        view.maxMemoryGi = entity.getMaxMemory();
        view.observedStatus = entity.getObservedStatus();
        view.registeredNodeId = entity.getRegisteredNodeId();
        view.labels = labels;
        view.lastSeenAt = entity.getLastSeenAt();
        return view;
    }

    public Long getCandidateId() { return candidateId; }
    public String getClusterId() { return clusterId; }
    public String getK8sUid() { return k8sUid; }
    public String getK8sNodeName() { return k8sNodeName; }
    public String getInternalIp() { return internalIp; }
    public String getExternalIp() { return externalIp; }
    public String getObservedRole() { return observedRole; }
    public Double getMaxCpu() { return maxCpu; }
    public Double getMaxMemoryGi() { return maxMemoryGi; }
    public String getObservedStatus() { return observedStatus; }
    public Integer getRegisteredNodeId() { return registeredNodeId; }
    public Map<String, String> getLabels() { return labels; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
}
