package org.example.mapper;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.entity.NodeManagement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * K8sNodeMapper 的默认实现，负责把 K8s Node 及其 Metrics 映射到 NodeManagement。
 */
@Component
public class K8sNodeMapperImpl implements K8sNodeMapper {

    private static final Logger log = LoggerFactory.getLogger(K8sNodeMapperImpl.class);

    @Override
    public NodeManagement toEntityWithMetrics(Node k8sNode, KubernetesClient k8sClient) {
        if (k8sNode == null) {
            return null;
        }

        String nodeName = k8sNode.getMetadata().getName();
        Map<String, String> labels = k8sNode.getMetadata().getLabels();
        Map<String, Quantity> capacity = k8sNode.getStatus().getCapacity();
        Map<String, Quantity> allocatable = k8sNode.getStatus().getAllocatable();

        double maxCpu = parseCpuToCores(capacity.get("cpu"));
        double maxMemoryGiB = parseMemoryToGiB(capacity.get("memory"));

        double allocatableCpu = parseCpuToCores(allocatable.get("cpu"));
        double allocatableMemoryGiB = parseMemoryToGiB(allocatable.get("memory"));

        double currentCpu = allocatableCpu;
        double currentMemoryGiB = allocatableMemoryGiB;

        // 从 Metrics 获取实时数据
        try {
            NodeMetrics metrics = k8sClient.top().nodes().metrics(nodeName);
            if (metrics != null && metrics.getUsage() != null) {
                Map<String, Quantity> usage = metrics.getUsage();

                Quantity cpuUsage = usage.get("cpu");
                if (cpuUsage != null) {
                    log.info("节点 {} CPU 原始 Quantity: amount='{}', format='{}'", nodeName, cpuUsage.getAmount(), cpuUsage.getFormat());
                    currentCpu = parseCpuToCores(cpuUsage);
                    log.info("节点 {} CPU 转换后: {}", nodeName, currentCpu);
                }

                Quantity memoryUsage = usage.get("memory");
                if (memoryUsage != null) {
                    log.info("节点 {} 内存原始 Quantity: amount='{}', format='{}'", nodeName, memoryUsage.getAmount(), memoryUsage.getFormat());
                    currentMemoryGiB = parseMemoryToGiB(memoryUsage);
                    log.info("节点 {} 内存转换后: {}", nodeName, currentMemoryGiB);
                }
            }
        } catch (Exception e) {
            log.error("无法获取节点 {} 的实时指标", nodeName, e);
        }

        NodeManagement.NodeManagementBuilder builder = NodeManagement.builder()
                .nodeName(nodeName)
                .type(extractRole(labels))
                .cluster(extractClusterName(labels))
                .maxCpu(maxCpu)
                .currentCpu(currentCpu)
                .maxMemory(maxMemoryGiB)
                .currentMemory(currentMemoryGiB)
                .numDataset(extractNumDataset(labels))
                .lastUpdateTime(LocalDateTime.now());

        k8sNode.getStatus().getAddresses().forEach(addr -> {
            if ("InternalIP".equals(addr.getType())) {
                builder.internalIp(addr.getAddress());
            }
            if ("ExternalIP".equals(addr.getType())) {
                builder.externalIp(addr.getAddress());
            }
        });

        NodeManagement entity = builder.build();
        log.info("节点 {} 数据: CPU {}/{}核, Memory {}/{}GiB",
                nodeName,
                String.format("%.2f", currentCpu), String.format("%.0f", maxCpu),
                String.format("%.2f", currentMemoryGiB), String.format("%.0f", maxMemoryGiB));
        return entity;
    }

    @Override
    public NodeManagement toEntity(Node k8sNode) {
        if (k8sNode == null) {
            return null;
        }

        Map<String, String> labels = k8sNode.getMetadata().getLabels();
        Map<String, Quantity> capacity = k8sNode.getStatus().getCapacity();
        Map<String, Quantity> allocatable = k8sNode.getStatus().getAllocatable();

        NodeManagement.NodeManagementBuilder builder = NodeManagement.builder()
                .nodeName(k8sNode.getMetadata().getName())
                .type(extractRole(labels))
                .cluster(extractClusterName(labels))
                .maxCpu(parseCpuToCores(capacity.get("cpu")))
                .maxMemory(parseMemoryToGiB(capacity.get("memory")))
                .currentCpu(parseCpuToCores(allocatable.get("cpu")))
                .currentMemory(parseMemoryToGiB(allocatable.get("memory")))
                .numDataset(extractNumDataset(labels))
                .lastUpdateTime(LocalDateTime.now());

        k8sNode.getStatus().getAddresses().forEach(addr -> {
            if ("InternalIP".equals(addr.getType())) {
                builder.internalIp(addr.getAddress());
            }
            if ("ExternalIP".equals(addr.getType())) {
                builder.externalIp(addr.getAddress());
            }
        });

        return builder.build();
    }

    private double parseCpuToCores(Quantity cpuQuantity) {
        if (cpuQuantity == null || cpuQuantity.getAmount() == null) {
            return 0.0;
        }
        String amount = cpuQuantity.getAmount().trim();
        String format = cpuQuantity.getFormat();
        try {
            double numValue = Double.parseDouble(amount);
            if ("n".equals(format)) {
                return numValue / 1_000_000_000.0;
            } else if ("m".equals(format)) {
                return numValue / 1000.0;
            } else {
                if (amount.endsWith("n")) {
                    return Double.parseDouble(amount.substring(0, amount.length() - 1)) / 1_000_000_000.0;
                } else if (amount.endsWith("m")) {
                    return Double.parseDouble(amount.substring(0, amount.length() - 1)) / 1000.0;
                }
                return numValue;
            }
        } catch (NumberFormatException e) {
            log.error("无法解析 CPU 值: amount='{}', format='{}'", amount, format, e);
            return 0.0;
        }
    }

    private double parseMemoryToGiB(Quantity memoryQuantity) {
        if (memoryQuantity == null || memoryQuantity.getAmount() == null) {
            return 0.0;
        }

        String amount = memoryQuantity.getAmount().trim();
        String format = memoryQuantity.getFormat();
        try {
            double numValue = Double.parseDouble(amount);
            double gibibytes;
            if ("Ki".equals(format)) {
                gibibytes = numValue / (1024.0 * 1024.0);
            } else if ("Mi".equals(format)) {
                gibibytes = numValue / 1024.0;
            } else if ("Gi".equals(format)) {
                gibibytes = numValue;
            } else if ("Ti".equals(format)) {
                gibibytes = numValue * 1024.0;
            } else {
                if (amount.endsWith("Ki")) {
                    return Double.parseDouble(amount.substring(0, amount.length() - 2)) / (1024.0 * 1024.0);
                } else if (amount.endsWith("Mi")) {
                    return Double.parseDouble(amount.substring(0, amount.length() - 2)) / 1024.0;
                } else if (amount.endsWith("Gi")) {
                    return Double.parseDouble(amount.substring(0, amount.length() - 2));
                } else if (amount.endsWith("Ti")) {
                    return Double.parseDouble(amount.substring(0, amount.length() - 2)) * 1024.0;
                }
                gibibytes = numValue / (1024.0 * 1024.0 * 1024.0);
            }
            return gibibytes;
        } catch (NumberFormatException e) {
            log.error("无法解析内存值: amount='{}', format='{}'", amount, format, e);
            return 0.0;
        }
    }

    private String extractRole(Map<String, String> labels) {
        if (labels == null) return "worker";
        // 优先读自定义标签 node-role（值即为中文类型，如"存储节点"）
        String customRole = labels.get("node-role");
        if (customRole != null && !customRole.isEmpty()) {
            return customRole;
        }
        // 回退到 K8s 标准标签 node-role.kubernetes.io/<role>
        return labels.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("node-role.kubernetes.io/"))
                .map(entry -> {
                    String roleKey = entry.getKey().substring("node-role.kubernetes.io/".length());
                    String roleValue = entry.getValue();
                    return roleValue != null && !roleValue.isEmpty() ? roleValue : roleKey;
                })
                .findFirst()
                .orElse("worker");
    }

    private String extractClusterName(Map<String, String> labels) {
        return Optional.ofNullable(labels)
                .map(lbls -> lbls.get("kubernetes.azure.com/cluster"))
                .orElse(null);
    }

    private Integer extractNumDataset(Map<String, String> labels) {
        return Optional.ofNullable(labels)
                .map(lbls -> lbls.get("num-datasets"))
                .map(Integer::valueOf)
                .orElse(0);
    }
}
