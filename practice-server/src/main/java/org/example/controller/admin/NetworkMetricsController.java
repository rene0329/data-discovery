package org.example.controller.admin;

import org.example.dto.NetworkEdgeDto;
import org.example.service.NetworkMetricsService;
import org.example.vo.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/network")
public class NetworkMetricsController {

    private static final Logger log = LoggerFactory.getLogger(NetworkMetricsController.class);

    @Autowired(required = false)
    private NetworkMetricsService networkMetricsService;

    @GetMapping("/allMetrics")
    public ResponseEntity<ApiResponse<List<NetworkEdgeDto>>> getAllNetworkMetrics() {
        try {
            if (networkMetricsService == null) {
                log.warn("NetworkMetricsService " +
                        "未注入，返回空列表。");
                return ResponseEntity.ok(ApiResponse.ok(Collections.emptyList()));
            }
            List<NetworkEdgeDto> edges = networkMetricsService.getAllEdgesWithNodeNames();
            return ResponseEntity.ok(ApiResponse.ok(edges));
        } catch (Exception e) {
            log.error("Failed to retrieve all network metrics.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(500, "Failed to retrieve all network metrics"));
        }
    }
}
