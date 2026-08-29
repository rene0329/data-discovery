package org.example.dto.registration;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.example.entity.NodeManagement;

import java.time.LocalDateTime;
import java.util.Map;

public class RegisteredNodeView {
    private Integer nodeId;
    private String clusterId;
    private String k8sUid;
    private String k8sNodeName;
    private String displayName;
    private String role;
    private String registrationStatus;
    private Boolean enabled;
    private String internalIp;
    private String externalIp;
    private Double maxCpu;
    private Double maxMemoryGi;
    private Double currentCpu;
    private Double currentMemoryGi;
    private Map<String, String> labels;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime lastSeenAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime verifiedAt;
    private Integer version;

    public static RegisteredNodeView from(NodeManagement entity, Map<String, String> labels) {
        RegisteredNodeView view = new RegisteredNodeView();
        view.nodeId = entity.getNodeId();
        view.clusterId = entity.getCluster();
        view.k8sUid = entity.getK8sUid();
        view.k8sNodeName = entity.getNodeName();
        view.displayName = entity.getDisplayName() == null ? entity.getNodeName() : entity.getDisplayName();
        view.role = toApiRole(entity.getType());
        view.registrationStatus = entity.getRegistrationStatus();
        view.enabled = entity.getEnabled();
        view.internalIp = entity.getInternalIp();
        view.externalIp = entity.getExternalIp();
        view.maxCpu = entity.getMaxCpu();
        view.maxMemoryGi = entity.getMaxMemory();
        view.currentCpu = entity.getCurrentCpu();
        view.currentMemoryGi = entity.getCurrentMemory();
        view.labels = labels;
        view.lastSeenAt = entity.getLastSeenAt();
        view.verifiedAt = entity.getVerifiedAt();
        view.version = entity.getRowVersion();
        return view;
    }

    private static String toApiRole(String role) {
        if (role == null) return "WORKER";
        return role.trim().replace('-', '_').toUpperCase();
    }

    public Integer getNodeId() { return nodeId; }
    public String getClusterId() { return clusterId; }
    public String getK8sUid() { return k8sUid; }
    public String getK8sNodeName() { return k8sNodeName; }
    public String getDisplayName() { return displayName; }
    public String getRole() { return role; }
    public String getRegistrationStatus() { return registrationStatus; }
    public Boolean getEnabled() { return enabled; }
    public String getInternalIp() { return internalIp; }
    public String getExternalIp() { return externalIp; }
    public Double getMaxCpu() { return maxCpu; }
    public Double getMaxMemoryGi() { return maxMemoryGi; }
    public Double getCurrentCpu() { return currentCpu; }
    public Double getCurrentMemoryGi() { return currentMemoryGi; }
    public Map<String, String> getLabels() { return labels; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public Integer getVersion() { return version; }
}
