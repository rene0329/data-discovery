package org.example.mapper;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.example.entity.NodeManagement;

public interface K8sNodeMapper {

    /**
     * 将 K8s Node 以及实时 Metrics 映射为 NodeManagement。
     * @param k8sNode Kubernetes 节点对象
     * @param k8sClient Kubernetes 客户端，用于获取 metrics
     * @return NodeManagement 实体；如果节点为空返回 null
     */
    NodeManagement toEntityWithMetrics(Node k8sNode, KubernetesClient k8sClient);

    /**
     * 仅根据 Node 状态映射（不调用 metrics）。
     * @param k8sNode Kubernetes 节点对象
     * @return NodeManagement 实体；如果节点为空返回 null
     */
    NodeManagement toEntity(Node k8sNode);
}
