package org.example.service;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.NodeManagement;
import org.example.factory.K8sJobFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final K8sJobFactory factory;

    /** Compatibility constructor for a known single-cluster client. */
    public NodeResourceService(KubernetesClient client) { this.client = client; this.factory = null; }

    @Autowired
    public NodeResourceService(K8sJobFactory factory) { this.factory = factory; this.client = null; }

    /** Unknown or unreadable nodes are absent from the ledger and never fit a request. */
    public NodeResourceLedger snapshot(Collection<NodeManagement> nodes) {
        NodeResourceLedger ledger = new NodeResourceLedger();
        for (NodeManagement node : nodes) {
            try {
                KubernetesClient selected = clientFor(node);
                if (selected == null) continue;
                Node k8sNode = selected.nodes().withName(node.getNodeName()).get();
                if (!eligible(k8sNode) || k8sNode.getStatus().getAllocatable() == null)
                    continue;
                Map<String, Quantity> allocatable = k8sNode.getStatus().getAllocatable();
                double[] free = {amount(allocatable.get("cpu")), amount(allocatable.get("memory")) / GIB,
                        amount(allocatable.get("nvidia.com/gpu")), amount(allocatable.get("ephemeral-storage")) / GIB};
                String key = NodeResourceLedger.key(node);
                double[] total = free.clone();
                java.util.Set<String> accountedJobs = new java.util.HashSet<>();
                int podCount = 0;
                for (Pod pod : selected.pods().inAnyNamespace()
                        .withField("spec.nodeName", node.getNodeName()).list().getItems()) {
                    String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
                    if ("Succeeded".equals(phase) || "Failed".equals(phase)) continue;
                    podCount++;
                    double[] requested = resourceRequests(pod.getSpec());
                    for (int i = 0; i < free.length; i++) free[i] -= requested[i];
                    if (pod.getMetadata() != null && "default".equals(pod.getMetadata().getNamespace())
                            && pod.getMetadata().getLabels() != null) {
                        String jobName = pod.getMetadata().getLabels().get("job-name");
                        if (jobName != null) accountedJobs.add(jobName);
                    }
                }
                ledger.putCapacity(key, total[0], total[1], total[2], total[3]);
                ledger.put(key, free[0], free[1], free[2], free[3]);
                double podCapacity = allocatable.containsKey("pods") ? amount(allocatable.get("pods")) : Double.POSITIVE_INFINITY;
                ledger.putPodSlots(key, podCapacity, podCapacity - podCount);
                accountedJobs.forEach(ledger::accountForJob);
            } catch (RuntimeException e) {
                log.warn("读取节点资源失败，节点暂不参与资源分配: node={} error={}",
                        NodeResourceLedger.key(node), e.getMessage());
            }
        }
        return ledger;
    }

    /** Jobs have no tolerations: do not repeatedly pick nodes the scheduler must reject. */
    static boolean eligible(Node node) {
        if (node == null || node.getStatus() == null || node.getStatus().getConditions() == null) return false;
        boolean ready = node.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
        if (!ready) return false;
        if (node.getSpec() == null) return true;
        if (Boolean.TRUE.equals(node.getSpec().getUnschedulable())) return false;
        return node.getSpec().getTaints() == null || node.getSpec().getTaints().stream()
                .noneMatch(taint -> "NoSchedule".equals(taint.getEffect()) || "NoExecute".equals(taint.getEffect()));
    }

    private KubernetesClient clientFor(NodeManagement node) {
        if (client != null) return client;
        Map<String, KubernetesClient> clients = factory.getClusterClients();
        KubernetesClient selected = node.getCluster() == null ? null : clients.get(node.getCluster());
        // The factory uses this same compatibility mapping in a single-cluster deployment.
        return selected != null ? selected : clients.size() == 1 ? clients.values().iterator().next() : null;
    }

    /** CPU, memory GiB, GPU count, ephemeral-storage GiB, including ordinary init containers. */
    public static double[] resourceRequests(PodSpec spec) {
        if (spec == null) return new double[]{0, 0, 0, 0};
        double[] running = new double[4];
        double[] initMaximum = new double[4];
        double[] restartable = new double[4];
        for (Container container : nullToEmpty(spec.getContainers())) add(running, resourceRequest(container));
        for (Container container : nullToEmpty(spec.getInitContainers())) {
            double[] one = resourceRequest(container);
            // Kubernetes restartable init containers keep consuming resources through later stages.
            if (container.getAdditionalProperties() != null
                    && "Always".equals(container.getAdditionalProperties().get("restartPolicy"))) {
                add(restartable, one);
                for (int i = 0; i < 4; i++) initMaximum[i] = Math.max(initMaximum[i], restartable[i]);
            } else {
                for (int i = 0; i < 4; i++) initMaximum[i] = Math.max(initMaximum[i], restartable[i] + one[i]);
            }
        }
        add(running, restartable);
        Map<String, Quantity> overhead = spec.getOverhead();
        String[] keys = {"cpu", "memory", "nvidia.com/gpu", "ephemeral-storage"};
        for (int i = 0; i < 4; i++) running[i] = Math.max(running[i], initMaximum[i])
                + (overhead == null ? 0 : amount(overhead.get(keys[i])) / (i == 1 || i == 3 ? GIB : 1));
        return running;
    }
    private static double[] resourceRequest(Container container) {
        Map<String, Quantity> requests = container.getResources() == null ? null : container.getResources().getRequests();
        if (requests == null) return new double[4];
        return new double[]{amount(requests.get("cpu")), amount(requests.get("memory")) / GIB,
                amount(requests.get("nvidia.com/gpu")), amount(requests.get("ephemeral-storage")) / GIB};
    }
    private static void add(double[] total, double[] one) {
        for (int i = 0; i < total.length; i++) total[i] += one[i];
    }

    /** CPU/memory compatibility view of the effective Pod request. */
    static double[] requests(PodSpec spec) {
        double[] all = resourceRequests(spec);
        return new double[]{all[0], all[1]};
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static double amount(Quantity quantity) {
        return quantity == null ? 0 : quantity.getNumericalAmount().doubleValue();
    }
}
