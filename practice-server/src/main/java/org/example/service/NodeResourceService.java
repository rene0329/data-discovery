package org.example.service;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.NodeManagement;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Free resources the way the K8s scheduler counts them: a node's allocatable CPU and memory minus
 * the requests of every pod bound to it that has not finished. Actual usage (metrics) is not used,
 * because a pod that requests more than allocatable minus requests stays Pending however idle the node is.
 */
@Service
@Slf4j
public class NodeResourceService {
    private static final double GIB = 1024d * 1024 * 1024;
    private final KubernetesClient client;

    public NodeResourceService(KubernetesClient client) {
        this.client = client;
    }

    /** Null when the cluster cannot be read; placement then ignores resources as before. */
    public NodeResourceLedger snapshot(Collection<NodeManagement> nodes) {
        NodeResourceLedger ledger = new NodeResourceLedger();
        try {
            for (NodeManagement node : nodes) {
                Node k8sNode = client.nodes().withName(node.getNodeName()).get();
                if (k8sNode == null || k8sNode.getStatus() == null || k8sNode.getStatus().getAllocatable() == null) {
                    continue;
                }
                Map<String, Quantity> allocatable = k8sNode.getStatus().getAllocatable();
                double cpu = amount(allocatable.get("cpu"));
                double memory = amount(allocatable.get("memory")) / GIB;
                for (Pod pod : client.pods().inAnyNamespace()
                        .withField("spec.nodeName", node.getNodeName()).list().getItems()) {
                    String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
                    if ("Succeeded".equals(phase) || "Failed".equals(phase)) continue;
                    double[] requested = requests(pod.getSpec());
                    cpu -= requested[0];
                    memory -= requested[1];
                }
                ledger.put(node.getNodeName(), cpu, memory);
            }
            return ledger;
        } catch (RuntimeException e) {
            log.warn("读取节点资源失败，本次放置不考虑节点资源: {}", e.getMessage());
            return null;
        }
    }

    /** A pod's effective request: max(sum of its containers, its largest init container) plus overhead. */
    static double[] requests(PodSpec spec) {
        if (spec == null) return new double[]{0, 0};
        double[] containers = sum(spec.getContainers());
        double[] init = {0, 0};
        for (Container container : nullToEmpty(spec.getInitContainers())) {
            double[] one = request(container);
            init[0] = Math.max(init[0], one[0]);
            init[1] = Math.max(init[1], one[1]);
        }
        Map<String, Quantity> overhead = spec.getOverhead();
        double overheadCpu = overhead == null ? 0 : amount(overhead.get("cpu"));
        double overheadMemory = overhead == null ? 0 : amount(overhead.get("memory")) / GIB;
        return new double[]{Math.max(containers[0], init[0]) + overheadCpu,
                Math.max(containers[1], init[1]) + overheadMemory};
    }

    private static double[] sum(List<Container> containers) {
        double[] total = {0, 0};
        for (Container container : nullToEmpty(containers)) {
            double[] one = request(container);
            total[0] += one[0];
            total[1] += one[1];
        }
        return total;
    }

    private static double[] request(Container container) {
        Map<String, Quantity> requests = container.getResources() == null ? null : container.getResources().getRequests();
        if (requests == null) return new double[]{0, 0};
        return new double[]{amount(requests.get("cpu")), amount(requests.get("memory")) / GIB};
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static double amount(Quantity quantity) {
        return quantity == null ? 0 : quantity.getNumericalAmount().doubleValue();
    }
}
