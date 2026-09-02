// src/main/java/org/example/service/NetworkProbeService.java
package org.example.service;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.dto.NetworkMetricDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * DaemonSet 网络探测服务
 * - 在每个节点上运行
 * - 探测到所有 storage / compute 节点的网络指标（延迟 + 带宽）
 * - 批量推送到中央服务
 */
@Service
public class NetworkProbeService {

    private static final Logger log = LoggerFactory.getLogger(NetworkProbeService.class);

    @Autowired
    private KubernetesClient k8sClient;

    @Autowired
    private RestTemplate restTemplate;

    // 中央服务接收指标的 URL
    @Value("${central.metrics.url:http://my-core-backend-central-service:8080/api/network/metrics/batch}")
    private String centralMetricsUrl;

    // 本地节点名称（通过 Downward API 注入）
    @Value("${local.node.name}")
    private String localNodeName;

    // 探测间隔（毫秒）
    @Value("${probe.interval.ms:600000}")
    private long probeIntervalMs;

    // 目标节点角色标签（逗号分隔：storage,compute）
    @Value("${probe.target.roles:storage,compute}")
    private String targetRoles;

    // 是否探测所有节点（忽略角色标签）
    @Value("${probe.all.nodes:false}")
    private boolean probeAllNodes;

    @Scheduled(initialDelayString = "${probe.initial-delay.ms:30000}",
            fixedDelayString = "${probe.interval.ms:600000}")
    public void probeAndPushNetworkMetrics() {
        log.info("开始网络探测，本地节点: {}", localNodeName);

        List<Node> targetNodes = getTargetNodes();
        if (targetNodes.isEmpty()) {
            log.warn("未找到任何目标节点，跳过本次探测");
            return;
        }

        List<NetworkMetricDto> metricsList = new ArrayList<>();

        for (Node node : targetNodes) {
            String targetNodeName = node.getMetadata().getName();
            if (targetNodeName.equals(localNodeName)) {
                continue; // 跳过自探测
            }
            // 每对节点只由名称较小的一端负责，避免两个方向并发覆盖同一条无向边。
            if (localNodeName.compareTo(targetNodeName) > 0) {
                continue;
            }

            String targetIP = getInternalIP(node);
            if (targetIP == null) {
                log.warn("节点 {} 无 InternalIP，跳过", targetNodeName);
                continue;
            }

            double latencyMs = probeLatency(targetIP);
            long bandwidthBps = probeBandwidth(targetIP);

            // Report failures too so fixed logical links become unavailable without disappearing.
            NetworkMetricDto dto = new NetworkMetricDto();
            dto.setSourceNode(localNodeName);
            dto.setTargetNode(targetNodeName);
            dto.setLatencyMs(latencyMs >= 0 ? latencyMs : null);
            dto.setBandwidthBps(bandwidthBps >= 0 ? bandwidthBps : null);
            dto.setMeasurementTime(System.currentTimeMillis());
            metricsList.add(dto);
        }

        if (!metricsList.isEmpty()) {
            pushMetricsToCentral(metricsList);
        } else {
            log.info("本次探测无有效数据");
        }
    }

    /**
     * 获取目标节点列表
     */
    private List<Node> getTargetNodes() {
        if (probeAllNodes) {
            return k8sClient.nodes().list().getItems();
        }

        Set<String> roles = Arrays.stream(targetRoles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        List<Node> result = new ArrayList<>();
        for (String role : roles) {
            List<Node> nodes = k8sClient.nodes()
                    .withLabel("node-role", role)
                    .list()
                    .getItems();
            result.addAll(nodes);
        }
        // 去重（防止节点有多个角色标签）
        return result.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 获取节点的 InternalIP
     */
    private String getInternalIP(Node node) {
        return Optional.ofNullable(node.getStatus())
                .map(status -> status.getAddresses())
                .flatMap(addresses -> addresses.stream()
                        .filter(addr -> "InternalIP".equals(addr.getType()))
                        .map(NodeAddress::getAddress)
                        .findFirst())
                .orElse(null);
    }

    /**
     * 使用 ping 探测延迟（返回平均延迟，单位：ms）
     */
    private double probeLatency(String targetIP) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"ping", "-c", "4", targetIP});
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                return -1;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("rtt min/avg/max/mdev")) {
                    String[] parts = line.split("/");
                    if (parts.length >= 5) {
                        return Double.parseDouble(parts[3]); // avg
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            log.debug("Ping 探测失败 {}: {}", targetIP, e.getMessage());
            return -1;
        }
    }

    /**
     * 使用 iperf3 探测带宽（返回 bps）
     * 需目标节点运行 iperf3 -s
     */
    private long probeBandwidth(String targetIP) {
        try {
            long forward = runIperf(targetIP, false);
            long reverse = runIperf(targetIP, true);
            if (forward < 0) return reverse;
            if (reverse < 0) return forward;
            return Math.min(forward, reverse);
        } catch (Exception e) {
            log.debug("iperf3 探测失败 {}: {}", targetIP, e.getMessage());
            return -1;
        }
    }

    private long runIperf(String targetIP, boolean reverse) {
        try {
            List<String> command = new ArrayList<>(Arrays.asList(
                    "iperf3", "-c", targetIP, "-t", "5", "-J"));
            if (reverse) command.add("-R");
            Process process = Runtime.getRuntime().exec(command.toArray(new String[0]));
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            // 简单解析 JSON 输出（iperf3 -J）
            String json = output.toString();
            if (json.contains("\"receiver\"") && json.contains("\"bits_per_second\"")) {
                // 提取 receiver 的 bps
                int start = json.lastIndexOf("\"bits_per_second\"");
                if (start != -1) {
                    String sub = json.substring(start + 18);
                    int end = sub.indexOf(",");
                    if (end != -1) {
                        String bpsStr = sub.substring(0, end).trim().replaceAll("[^0-9.]", "");
                        return (long) Double.parseDouble(bpsStr);
                    }
                }
            }
            return -1;
        } catch (Exception e) {
            log.debug("iperf3 {}探测失败 {}: {}", reverse ? "反向" : "正向", targetIP, e.getMessage());
            return -1;
        }
    }

    /**
     * 批量推送指标到中央服务
     */
    private void pushMetricsToCentral(List<NetworkMetricDto> metricsList) {
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    centralMetricsUrl, metricsList, String.class
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("成功推送 {} 条网络指标到中央服务", metricsList.size());
            } else {
                log.warn("推送失败，状态码: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("推送网络指标失败: {}", e.getMessage());
        }
    }
}
