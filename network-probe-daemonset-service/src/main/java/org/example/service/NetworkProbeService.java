// src/main/java/org/example/service/NetworkProbeService.java
package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.dto.ExternalNodeHeartbeatDto;
import org.example.dto.NetworkMetricDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private KubernetesClient k8sClient;

    @Autowired
    private RestTemplate restTemplate;

    // 中央服务接收指标的 URL
    @Value("${central.metrics.url:http://my-core-backend-central-service:8080/api/network/metrics/batch}")
    private String centralMetricsUrl;

    @Value("${central.auth-token:}")
    private String centralAuthToken;

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

    // 只探测业务拓扑中实际配置的边，格式为 node-a/node-b,node-c/node-d。
    @Value("${probe.target.edges:}")
    private String targetEdges;

    // External links are probed by one designated ZJ node because only that host owns the SSH TUNs.
    // Format: remoteNode@tunnelIp/logicalLocalNode,remoteNode@tunnelIp/logicalLocalNode
    @Value("${probe.external.edges:}")
    private String externalEdges;

    @Value("${probe.external.runner-node:}")
    private String externalRunnerNode;

    @Value("${probe.external.cluster-id:zj-external-aliyun}")
    private String externalClusterId;

    // Same-region pairs (e.g. two nodes in one Aliyun VPC) skip the SSH-tunnel overlay
    // address and probe over the cloud's own private network instead.
    // Format: node-name=vpc-private-ip,node-name=vpc-private-ip
    @Value("${probe.node.lan-ip-overrides:}")
    private String lanIpOverrides;

    @Value("${central.node-heartbeat.url:}")
    private String centralNodeHeartbeatUrl;

    // Fixed-duration test per direction: a fixed byte count under-measures fast,
    // low-latency links (transfer finishes during TCP slow start), while a fixed
    // duration reaches steady-state throughput on any link and keeps total probe
    // time bounded regardless of the link's actual speed.
    @Value("${probe.transfer.seconds:2}")
    private int probeTransferSeconds;

    @Scheduled(initialDelayString = "${probe.initial-delay.ms:30000}",
            fixedDelayString = "${probe.interval.ms:86400000}")
    public void probeAndPushNetworkMetrics() {
        log.info("开始网络探测，本地节点: {}", localNodeName);

        List<Node> targetNodes = getTargetNodes();
        if (targetNodes.isEmpty()) {
            log.warn("未找到任何集群内目标节点");
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
            if (!isTargetEdge(localNodeName, targetNodeName, targetEdges)) {
                continue;
            }

            String targetIP = resolveTargetIp(targetNodeName, node);
            if (targetIP == null) {
                log.warn("节点 {} 无 InternalIP，跳过", targetNodeName);
                continue;
            }

            double latencyMs = probeLatency(targetIP);
            long bandwidthBps = probeBandwidth(targetIP);
            if (latencyMs < 0 || bandwidthBps <= 0) {
                log.warn("网络探测不完整 {} -> {}: latencyMs={}, bandwidthBps={}",
                        localNodeName, targetNodeName, latencyMs, bandwidthBps);
            }

            // Report failures too so fixed logical links become unavailable without disappearing.
            NetworkMetricDto dto = new NetworkMetricDto();
            dto.setSourceNode(localNodeName);
            dto.setTargetNode(targetNodeName);
            dto.setLatencyMs(latencyMs >= 0 ? latencyMs : null);
            dto.setBandwidthBps(bandwidthBps >= 0 ? bandwidthBps : null);
            dto.setMeasurementTime(System.currentTimeMillis());
            metricsList.add(dto);
        }

        appendExternalMetrics(metricsList);

        if (!metricsList.isEmpty()) {
            pushMetricsToCentral(metricsList);
        } else {
            log.info("本次探测无有效数据");
        }
    }

    private void appendExternalMetrics(List<NetworkMetricDto> metricsList) {
        if (!isExternalRunner()) return;
        for (ExternalProbeTarget target : parseExternalTargets(externalEdges)) {
            double latencyMs = probeLatency(target.tunnelIp);
            long bandwidthBps = probeBandwidth(target.tunnelIp);
            NetworkMetricDto dto = new NetworkMetricDto();
            dto.setSourceNode(target.logicalLocalNode);
            dto.setTargetNode(target.remoteNode);
            dto.setLatencyMs(latencyMs >= 0 ? latencyMs : null);
            dto.setBandwidthBps(bandwidthBps >= 0 ? bandwidthBps : null);
            dto.setMeasurementTime(System.currentTimeMillis());
            metricsList.add(dto);
        }
    }

    @Scheduled(initialDelayString = "${probe.external.heartbeat.initial-delay.ms:30000}",
            fixedDelayString = "${probe.external.heartbeat.interval.ms:60000}")
    public void probeExternalHeartbeats() {
        if (!isExternalRunner() || centralNodeHeartbeatUrl == null
                || centralNodeHeartbeatUrl.trim().isEmpty()) return;
        for (ExternalProbeTarget target : parseExternalTargets(externalEdges)) {
            double latencyMs = probeLatency(target.tunnelIp);
            pushExternalHeartbeat(target, latencyMs >= 0,
                    latencyMs >= 0 ? null : "SSH tunnel ping failed from " + localNodeName);
        }
    }

    private boolean isExternalRunner() {
        return localNodeName != null && externalRunnerNode != null
                && localNodeName.equals(externalRunnerNode.trim());
    }

    private void pushExternalHeartbeat(ExternalProbeTarget target, boolean online, String reason) {
        try {
            ExternalNodeHeartbeatDto heartbeat = new ExternalNodeHeartbeatDto();
            heartbeat.setClusterId(externalClusterId);
            heartbeat.setNodeName(target.remoteNode);
            heartbeat.setInternalIp(target.tunnelIp);
            heartbeat.setOnline(online);
            heartbeat.setReason(reason);
            HttpHeaders headers = new HttpHeaders();
            if (centralAuthToken != null && !centralAuthToken.trim().isEmpty()) {
                headers.setBearerAuth(centralAuthToken.trim());
            }
            restTemplate.exchange(centralNodeHeartbeatUrl, HttpMethod.POST,
                    new HttpEntity<>(heartbeat, headers), String.class);
        } catch (Exception e) {
            log.error("推送外部节点 {} 心跳失败: {}", target.remoteNode, e.getMessage());
        }
    }

    static List<ExternalProbeTarget> parseExternalTargets(String configured) {
        if (configured == null || configured.trim().isEmpty()) return Collections.emptyList();
        List<ExternalProbeTarget> targets = new ArrayList<>();
        for (String value : configured.split(",")) {
            String[] sides = value.trim().split("/", -1);
            if (sides.length != 2) continue;
            int at = sides[0].lastIndexOf('@');
            if (at <= 0 || at == sides[0].length() - 1 || sides[1].trim().isEmpty()) continue;
            targets.add(new ExternalProbeTarget(sides[0].substring(0, at).trim(),
                    sides[0].substring(at + 1).trim(), sides[1].trim()));
        }
        return targets;
    }

    static final class ExternalProbeTarget {
        final String remoteNode;
        final String tunnelIp;
        final String logicalLocalNode;

        ExternalProbeTarget(String remoteNode, String tunnelIp, String logicalLocalNode) {
            this.remoteNode = remoteNode;
            this.tunnelIp = tunnelIp;
            this.logicalLocalNode = logicalLocalNode;
        }
    }

    private String resolveTargetIp(String targetNodeName, Node targetNode) {
        String override = parseIpOverrides(lanIpOverrides).get(targetNodeName);
        return override != null ? override : getInternalIP(targetNode);
    }

    static Map<String, String> parseIpOverrides(String configured) {
        Map<String, String> overrides = new HashMap<>();
        if (configured == null || configured.trim().isEmpty()) return overrides;
        for (String entry : configured.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
                overrides.put(parts[0].trim(), parts[1].trim());
            }
        }
        return overrides;
    }

    static boolean isTargetEdge(String firstNode, String secondNode, String configuredEdges) {
        if (configuredEdges == null || configuredEdges.trim().isEmpty()) return false;
        for (String configuredEdge : configuredEdges.split(",")) {
            String[] nodes = configuredEdge.trim().split("/", -1);
            if (nodes.length != 2) continue;
            String first = nodes[0].trim();
            String second = nodes[1].trim();
            if ((firstNode.equals(first) && secondNode.equals(second))
                    || (firstNode.equals(second) && secondNode.equals(first))) {
                return true;
            }
        }
        return false;
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
    double probeLatency(String targetIP) {
        try {
            ProcessBuilder command = new ProcessBuilder("ping", "-n", "-c", "4", targetIP);
            command.environment().put("LC_ALL", "C");
            Process process = command.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
            if (process.exitValue() != 0) return -1;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                return parseLatency(reader.lines().collect(Collectors.joining("\n")));
            }
        } catch (Exception e) {
            log.debug("Ping 探测失败 {}: {}", targetIP, e.getMessage());
            return -1;
        }
    }

    static double parseLatency(String output) {
        for (String line : output.split("\\r?\\n")) {
            if (!line.contains("min/avg/max")) continue;
            int separator = line.indexOf('=');
            if (separator < 0) continue;
            // Only split the numeric right-hand side; the header also contains slashes.
            String[] values = line.substring(separator + 1).trim().split("/");
            if (values.length < 3) continue;
            try {
                double average = Double.parseDouble(values[1].trim());
                return Double.isFinite(average) && average >= 0 ? average : -1;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 使用 iperf3 探测带宽（返回 bps）
     * 需目标节点运行 iperf3 -s
     */
    long probeBandwidth(String targetIP) {
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

    long runIperf(String targetIP, boolean reverse) {
        try {
            for (int attempt = 0; attempt <= 3; attempt++) {
                String output = runIperfCommand(targetIP, reverse);
                if (output == null) return -1;
                if (isIperfBusy(output) && attempt < 3) {
                    log.info("iperf3 服务忙 {}，等待 {} 秒后重试", targetIP, 5 * (attempt + 1));
                    waitForIperfRetry(attempt + 1);
                    continue;
                }
                return parseBandwidth(output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return -1;
    }

    void waitForIperfRetry(int retry) throws InterruptedException {
        TimeUnit.SECONDS.sleep(5L * retry);
    }

    String runIperfCommand(String targetIP, boolean reverse) {
        try {
            List<String> command = new ArrayList<>(Arrays.asList(
                    "iperf3", "-c", targetIP, "-t", Integer.toString(probeTransferSeconds), "-J"));
            if (reverse) command.add("-R");
            Process process = Runtime.getRuntime().exec(command.toArray(new String[0]));
            if (!process.waitFor(probeTransferSeconds + 15L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String output = reader.lines().collect(Collectors.joining("\n"));
                // iperf exits nonzero when another client owns the server. Preserve that
                // response for bounded retries instead of treating contention as a broken link.
                return process.exitValue() == 0 || isIperfBusy(output) ? output : null;
            }
        } catch (Exception e) {
            log.debug("iperf3 {}探测失败 {}: {}", reverse ? "反向" : "正向", targetIP, e.getMessage());
            return null;
        }
    }

    static boolean isIperfBusy(String output) {
        try {
            JsonNode result = JSON.readTree(output);
            return result != null && result.path("error").asText("")
                    .contains("server is busy running a test");
        } catch (java.io.IOException e) {
            return false;
        }
    }

    static long parseBandwidth(String output) {
        try {
            JsonNode result = JSON.readTree(output);
            if (result == null || result.hasNonNull("error")) return -1;
            JsonNode rate = result.path("end").path("sum_received").path("bits_per_second");
            if (!rate.isNumber()) return -1;
            double bps = rate.doubleValue();
            return Double.isFinite(bps) && bps >= 1 && bps < Long.MAX_VALUE ? (long) bps : -1;
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    /**
     * 批量推送指标到中央服务
     */
    private void pushMetricsToCentral(List<NetworkMetricDto> metricsList) {
        try {
            HttpHeaders headers = new HttpHeaders();
            if (centralAuthToken != null && !centralAuthToken.trim().isEmpty()) {
                headers.setBearerAuth(centralAuthToken.trim());
            }
            ResponseEntity<String> response = restTemplate.exchange(centralMetricsUrl, HttpMethod.POST,
                    new HttpEntity<>(metricsList, headers), String.class);
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
