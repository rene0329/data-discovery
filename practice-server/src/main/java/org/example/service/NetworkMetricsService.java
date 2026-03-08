// src/main/java/org/example/service/NetworkMetricsService.java
package org.example.service;

import org.example.dto.NetworkMetricDto; // <--- 确保使用您项目的 DTO
import org.example.dto.NetworkEdgeDto; // <--- 新增的 DTO，用于前端展示
import org.example.entity.EdgeManagement; // <--- 确保使用您项目的 Entity
import org.example.entity.NodeManagement;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
@Service
public class NetworkMetricsService {

    private static final Logger log = LoggerFactory.getLogger(NetworkMetricsService.class);

    @Autowired
    private EdgeManagementMapper edgeManagementMapper;
    @Autowired
    private NodeManagementMapper nodeManagementMapper;

    /**
     * 接收并处理批量网络探测数据，遍历调用您现有的 saveMetrics 方法。
     * (这个方法名和参数与 Controller 保持一致)
     * @param metricsDtoList 接收到的网络指标列表
     */
    @Transactional
    public void processNetworkMetrics(List<NetworkMetricDto> metricsDtoList) { // <--- 方法名修正
        if (metricsDtoList == null || metricsDtoList.isEmpty()) {
            return;
        }
        for (NetworkMetricDto dto : metricsDtoList) {
            saveMetrics(dto); // 调用您现有的 saveMetrics 方法
        }
    }

    /**
     * 接收并处理单个网络探测数据。
     * (这是您已有的方法，保持不变，添加 @Transactional 注解)
     * @param metricsDto 单个网络指标 DTO
     */
    @Transactional
    public void saveMetrics(NetworkMetricDto metricsDto) {
        NodeManagement sourceNode = nodeManagementMapper.getNodeByName(metricsDto.getSourceNode());
        NodeManagement targetNode = nodeManagementMapper.getNodeByName(metricsDto.getTargetNode());

        if (sourceNode == null) {
            log.warn("Source node '{}' not found in database. Skipping network metric save.", metricsDto.getSourceNode());
            return;
        }
        if (targetNode == null) {
            log.warn("Target node '{}' not found in database. Skipping network metric save.", metricsDto.getTargetNode());
            return;
        }

        Integer sourceNodeId = sourceNode.getNodeId();
        Integer targetNodeId = targetNode.getNodeId();

        EdgeManagement existingEdge = edgeManagementMapper.findBySourceAndTargetNode(sourceNodeId, targetNodeId);

        if (existingEdge != null) {
            existingEdge.setBandwidth(metricsDto.getBandwidthBps());
            existingEdge.setLatency(metricsDto.getLatencyMs());
            existingEdge.setStatus("UP");
            edgeManagementMapper.updateEdge(existingEdge);
            log.debug("Updated network metrics for link {} (ID: {}) -> {} (ID: {}). Bandwidth: {} bps, Latency: {} ms",
                    metricsDto.getSourceNode(), sourceNodeId, metricsDto.getTargetNode(), targetNodeId,
                    metricsDto.getBandwidthBps(), metricsDto.getLatencyMs());
        } else {
            EdgeManagement newEdge = EdgeManagement.builder()
                    .sourceId(sourceNodeId)
                    .targetId(targetNodeId)
                    .bandwidth(metricsDto.getBandwidthBps())
                    .latency(metricsDto.getLatencyMs())
                    .status("UP")
                    .build();
            edgeManagementMapper.insertEdge(newEdge);
            log.info("Inserted new network metrics for link {} (ID: {}) -> {} (ID: {}). Bandwidth: {} bps, Latency: {} ms",
                    metricsDto.getSourceNode(), sourceNodeId, metricsDto.getTargetNode(), targetNodeId,
                    metricsDto.getBandwidthBps(), metricsDto.getLatencyMs());
        }
    }

    /**
     * 获取所有网络链路数据，并补充源/目标节点名称，供前端展示。
     * 由于 EdgeManagement 实体中没有 sourceNodeName/targetNodeName，
     * 我们创建一个 NetworkEdgeDto 来返回这些组合信息。
     *
     * @return 包含 sourceNodeName 和 targetNodeName 的 NetworkEdgeDto 列表
     */
    public List<NetworkEdgeDto> getAllEdgesWithNodeNames() { // <--- 返回类型修正为 List<NetworkEdgeDto>
        List<EdgeManagement> edges = edgeManagementMapper.selectAllEdges();
        if (edges == null || edges.isEmpty()) {
            return new ArrayList<>();
        }

        List<NodeManagement> allNodes = nodeManagementMapper.selectAllNodes();
        Map<Integer, String> nodeIdToNameMap = allNodes.stream()
                .collect(Collectors.toMap(NodeManagement::getNodeId, NodeManagement::getNodeName));

        return edges.stream().map(edge -> {
            String sourceName = nodeIdToNameMap.getOrDefault(edge.getSourceId(), "Unknown");
            String targetName = nodeIdToNameMap.getOrDefault(edge.getTargetId(), "Unknown");

            // 构建 NetworkEdgeDto
            return NetworkEdgeDto.builder()
                    .edgeId(edge.getEdgeId())
                    .sourceId(edge.getSourceId())
                    .targetId(edge.getTargetId())
                    .sourceNodeName(sourceName) // 填充节点名称
                    .targetNodeName(targetName) // 填充节点名称
                    .bandwidth(edge.getBandwidth())
                    .latency(edge.getLatency())
                    .status(edge.getStatus())
                    .measurementTime(edge.getMeasurementTime() != null ? edge.getMeasurementTime().getTime() : Instant.now().toEpochMilli())
                    .build();
        }).collect(Collectors.toList());
    }
}
