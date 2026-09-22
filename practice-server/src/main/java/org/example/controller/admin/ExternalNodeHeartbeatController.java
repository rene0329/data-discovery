package org.example.controller.admin;

import org.example.dto.ExternalNodeHeartbeatDto;
import org.example.mapper.NodeManagementMapper;
import org.example.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExternalNodeHeartbeatController {
    private final NodeManagementMapper nodeMapper;
    private final int offlineFailureThreshold;

    public ExternalNodeHeartbeatController(
            NodeManagementMapper nodeMapper,
            @Value("${app.node-sync.offline-failure-threshold:3}") int offlineFailureThreshold) {
        this.nodeMapper = nodeMapper;
        this.offlineFailureThreshold = Math.max(1, offlineFailureThreshold);
    }

    @PostMapping("/api/network/nodes/heartbeat")
    public ResponseEntity<ApiResponse<Void>> report(@RequestBody ExternalNodeHeartbeatDto report) {
        if (report == null || !StringUtils.hasText(report.getClusterId())
                || !StringUtils.hasText(report.getNodeName())
                || !StringUtils.hasText(report.getInternalIp()) || report.getOnline() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400,
                    "Cluster, node, internal IP and online state are required"));
        }
        String cluster = report.getClusterId().trim();
        String nodeName = report.getNodeName().trim();
        String internalIp = report.getInternalIp().trim();
        int updated;
        if (Boolean.TRUE.equals(report.getOnline())) {
            updated = nodeMapper.markExternalNodeOnline(cluster, nodeName, internalIp);
        } else {
            String reason = StringUtils.hasText(report.getReason())
                    ? report.getReason().trim() : "External node tunnel is unreachable";
            if (reason.length() > 512) reason = reason.substring(0, 512);
            updated = nodeMapper.markExternalNodeUnreachable(
                    cluster, nodeName, internalIp, reason, offlineFailureThreshold);
        }
        if (updated == 0) {
            return ResponseEntity.status(404).body(ApiResponse.error(404,
                    "Registered external node identity not found"));
        }
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
