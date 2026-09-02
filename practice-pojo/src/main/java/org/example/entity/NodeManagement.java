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
    private String displayName;


    private String externalIp;
    private String internalIp;
    private String type;
    private String cluster;
    private String k8sUid;

    private Double maxCpu;
    private Double maxMemory;

    private Double currentCpu;
    private Double currentMemory;

    private Integer numDataset;
    private LocalDateTime lastUpdateTime;

    // 注册状态由管理面维护；K8s 同步只更新观测字段。
    private String registrationStatus;
    private Boolean enabled;
    // Kubernetes/Agent observation state; never replaces administrator intent in enabled.
    private String observedStatus;
    private String observedStatusReason;
    private Integer offlineObservationCount;
    private String labelsJson;
    private LocalDateTime lastSeenAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime deletedAt;
    private Integer rowVersion;
    // Derived response fields; not persisted by MyBatis.
    private String effectiveStatus;
    private Boolean schedulable;
    private String statusReason;

}
