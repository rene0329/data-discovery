package org.example.service;

import org.example.entity.NodeManagement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class NodeAvailabilityService {
    private final long staleAfterSeconds;

    public NodeAvailabilityService(
            @Value("${app.node-sync.stale-after-seconds:300}") long staleAfterSeconds) {
        this.staleAfterSeconds = Math.max(1L, staleAfterSeconds);
    }

    public NodeAvailability evaluate(NodeManagement node) {
        if (node == null || node.getDeletedAt() != null) {
            return unavailable("DELETED", "节点已注销");
        }
        if (!Boolean.TRUE.equals(node.getEnabled())) {
            return unavailable("DISABLED", "节点未启用");
        }
        if (!"ACTIVE".equals(node.getRegistrationStatus())) {
            return unavailable("INACTIVE", "节点注册状态为 " + safe(node.getRegistrationStatus()));
        }

        String observed = safe(node.getObservedStatus());
        if ("OFFLINE".equals(observed)) {
            return unavailable("OFFLINE", reason(node, "Kubernetes 节点离线"));
        }
        if ("NOT_READY".equals(observed)) {
            return unavailable("NOT_READY", reason(node, "Kubernetes 节点未就绪"));
        }
        if ("UNSCHEDULABLE".equals(observed)) {
            return unavailable("UNSCHEDULABLE", reason(node, "Kubernetes 节点不可调度"));
        }
        if ("AGENT_UNHEALTHY".equals(observed)) {
            return unavailable("AGENT_UNHEALTHY", reason(node, "数据发现 Agent 不健康"));
        }
        if (!"ONLINE".equals(observed)) {
            return unavailable("UNKNOWN", reason(node, "节点运行状态未知"));
        }
        if (node.getLastSeenAt() == null || node.getLastSeenAt().isBefore(
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(staleAfterSeconds))) {
            return unavailable("OFFLINE", "节点观测信息已超过 " + staleAfterSeconds + " 秒未更新");
        }
        return new NodeAvailability("AVAILABLE", true, null);
    }

    public boolean isSchedulable(NodeManagement node) {
        return evaluate(node).isSchedulable();
    }

    private NodeAvailability unavailable(String status, String reason) {
        return new NodeAvailability(status, false, reason);
    }

    private String reason(NodeManagement node, String fallback) {
        return node.getObservedStatusReason() == null || node.getObservedStatusReason().trim().isEmpty()
                ? fallback : node.getObservedStatusReason();
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "UNKNOWN" : value.trim().toUpperCase();
    }
}
