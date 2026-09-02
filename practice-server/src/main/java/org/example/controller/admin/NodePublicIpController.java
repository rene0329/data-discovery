package org.example.controller.admin;

import org.example.dto.NodePublicIpDto;
import org.example.mapper.NodeManagementMapper;
import org.example.utils.PublicIpv4;
import org.example.vo.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NodePublicIpController {
    private final NodeManagementMapper nodeMapper;

    public NodePublicIpController(NodeManagementMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    @PostMapping("/api/network/nodes/public-ip")
    public ResponseEntity<ApiResponse<Void>> report(@RequestBody NodePublicIpDto report) {
        String ip = report == null ? null : PublicIpv4.normalize(report.getPublicIp());
        if (ip == null || !StringUtils.hasText(report.getClusterId())
                || !StringUtils.hasText(report.getNodeName()) || !StringUtils.hasText(report.getK8sUid())) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "Node identity and a public IPv4 are required"));
        }
        // Exact identity match prevents assigning one node's IP to a same-named node in another cluster.
        int updated = nodeMapper.updateObservedPublicIp(report.getClusterId(), report.getK8sUid(), report.getNodeName(), ip);
        if (updated == 0) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Registered node identity not found"));
        }
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
