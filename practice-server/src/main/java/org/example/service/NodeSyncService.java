////package org.example.service;
////
////import io.fabric8.kubernetes.api.model.Node;
////import io.fabric8.kubernetes.client.KubernetesClient;
////import io.fabric8.kubernetes.client.Watcher;
////import io.fabric8.kubernetes.client.WatcherException;
////import org.example.entity.NodeManagement;
////import org.example.mapper.K8sNodeMapper;
////import org.example.mapper.NodeManagementMapper;
////import org.slf4j.Logger;
////import org.slf4j.LoggerFactory;
////import org.springframework.beans.factory.annotation.Autowired;
////import org.springframework.stereotype.Service;
////
////import javax.annotation.PostConstruct;
////import java.util.List;
////
////@Service
////public class NodeSyncService {
////
////    private static final Logger log = LoggerFactory.getLogger(NodeSyncService.class);
////
////    @Autowired
////    private KubernetesClient client; // 假设从 K8sJobFactory 或其他注入
////
////    @Autowired
////    private NodeManagementMapper nodeManagementMapper;
////
////    @Autowired
////    private K8sNodeMapper k8sNodeMapper;
////
////    @PostConstruct
////    public void init() {
////        log.info("Starting initial full sync of nodes...");
////        initialFullSync();
////        log.info("Initial full sync of nodes completed.");
////
////        startNodeWatcher();
////        log.info("Node watcher started successfully.");
////    }
////
////    /**
////     * 初始全量同步：获取所有节点，逐个 upsert。
////     */
////    private void initialFullSync() {
////        List<Node> nodes = client.nodes().list().getItems();
////        nodes.forEach(this::syncNode);
////    }
////
////    /**
////     * Upsert 逻辑：存在则 update，否则 insert。
////     * @param k8sNode K8s Node 对象
////     */
////    private void syncNode(Node k8sNode) {
////        NodeManagement entity = k8sNodeMapper.toEntity(k8sNode);
////        if (entity == null) {
////            log.warn("Skipping null entity for node: {}", k8sNode.getMetadata().getName());
////            return;
////        }
////
////        // 检查数据库中是否存在
////        NodeManagement existing = nodeManagementMapper.getNodeByName(entity.getNodeName());
////        if (existing != null) {
////            // 存在：更新（保留原有 node_id）
////            entity.setNodeId(existing.getNodeId());
////            nodeManagementMapper.updateNodeFromK8s(entity);
////            log.info("Node '{}' static info updated in the database.", entity.getNodeName());
////        } else {
////            // 不存在：插入
////            nodeManagementMapper.insertNode(entity);
////            log.info("Node '{}' inserted into the database.", entity.getNodeName());
////        }
////    }
////
////    /**
////     * 启动 K8s Node Watcher，监听事件并同步。
////     */
////    private void startNodeWatcher() {
////        client.nodes().watch(new Watcher<Node>() {
////            @Override
////            public void eventReceived(Action action, Node node) {
////                String nodeName = node.getMetadata().getName();
////                switch (action) {
////                    case ADDED:
////                    case MODIFIED:
////                        log.info("Node event: {} for {}", action, nodeName);
////                        syncNode(node);
////                        break;
////                    case DELETED:
////                        log.info("Node deleted: {}", nodeName);
////                        nodeManagementMapper.deleteByName(nodeName);
////                        break;
////                    case ERROR:
////                        log.error("Error event for node: {}", nodeName);
////                        break;
////                }
////            }
////
////            @Override
////            public void onClose(WatcherException cause) {
////                log.warn("Node watcher closed", cause);
////                // 可添加重连逻辑
////            }
////        });
////    }
////}
//
//
//package org.example.service;
//
//import io.fabric8.kubernetes.api.model.Node;
//import io.fabric8.kubernetes.client.KubernetesClient;
//import io.fabric8.kubernetes.client.Watcher;
//import io.fabric8.kubernetes.client.WatcherException;
//import org.example.entity.NodeManagement;
//import org.example.mapper.K8sNodeMapper;
//import org.example.mapper.NodeManagementMapper;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Service;
//
//import javax.annotation.PostConstruct;
//import java.util.List;
//
//@Service
//public class NodeSyncService {
//
//    private static final Logger log = LoggerFactory.getLogger(NodeSyncService.class);
//
//    @Autowired
//    private KubernetesClient client;
//
//    @Autowired
//    private NodeManagementMapper nodeManagementMapper;
//
//    @Autowired
//    private K8sNodeMapper k8sNodeMapper;
//
//    @PostConstruct
//    public void init() {
//        log.info("Starting initial full sync of nodes...");
//        initialFullSync();
//        log.info("Initial full sync of nodes completed.");
//
//        startNodeWatcher();
//        log.info("Node watcher started successfully.");
//    }
//
//    /**
//     * 初始全量同步：获取所有节点，逐个 upsert（包含 Metrics 数据）
//     */
//    private void initialFullSync() {
//        List<Node> nodes = client.nodes().list().getItems();
//        nodes.forEach(this::syncNode);
//    }
//
//    /**
//     * 【新增】定时任务：每 30 秒更新一次节点的实时 Metrics
//     * 因为 Watcher 不会主动推送 Metrics 变化，需要定时轮询
//     */
//    @Scheduled(fixedRate = 30000) // 每 30 秒执行一次
//    public void updateNodeMetrics() {
//        log.debug("定时更新节点 Metrics...");
//        List<Node> nodes = client.nodes().list().getItems();
//        nodes.forEach(this::syncNode);
//    }
//
//    /**
//     * Upsert 逻辑：存在则 update，否则 insert
//     * ✅ 使用 toEntityWithMetrics 获取实时数据
//     */
//    private void syncNode(Node k8sNode) {
//        // ✅ 关键：使用 toEntityWithMetrics 获取包含 Metrics 的数据
//        NodeManagement entity = k8sNodeMapper.toEntityWithMetrics(k8sNode, client);
//
//        if (entity == null) {
//            log.warn("Skipping null entity for node: {}", k8sNode.getMetadata().getName());
//            return;
//        }
//
//        // 检查数据库中是否存在
//        NodeManagement existing = nodeManagementMapper.getNodeByName(entity.getNodeName());
//        if (existing != null) {
//            // 存在：更新（保留原有 node_id）
//            entity.setNodeId(existing.getNodeId());
//            nodeManagementMapper.updateNodeFromK8s(entity);
//            log.debug("Node '{}' updated: CPU={}/{}, Memory={}/{}GB",
//                    entity.getNodeName(),
//                    entity.getCurrentCpu(), entity.getMaxCpu(),
//                    entity.getCurrentMemory(), entity.getMaxMemory());
//        } else {
//            // 不存在：插入
//            nodeManagementMapper.insertNode(entity);
//            log.info("Node '{}' inserted into the database.", entity.getNodeName());
//        }
//    }
//
//    /**
//     * 启动 K8s Node Watcher，监听节点的增删改事件
//     */
//    private void startNodeWatcher() {
//        client.nodes().watch(new Watcher<Node>() {
//            @Override
//            public void eventReceived(Action action, Node node) {
//                String nodeName = node.getMetadata().getName();
//                switch (action) {
//                    case ADDED:
//                    case MODIFIED:
//                        log.info("Node event: {} for {}", action, nodeName);
//                        syncNode(node);
//                        break;
//                    case DELETED:
//                        log.info("Node deleted: {}", nodeName);
//                        nodeManagementMapper.deleteByName(nodeName);
//                        break;
//                    case ERROR:
//                        log.error("Error event for node: {}", nodeName);
//                        break;
//                }
//            }
//
//            @Override
//            public void onClose(WatcherException cause) {
//                log.warn("Node watcher closed", cause);
//                // 可添加重连逻辑
//                try {
//                    Thread.sleep(5000);
//                    startNodeWatcher();
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                }
//            }
//        });
//    }
//}

package org.example.service;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import org.example.entity.NodeManagement;
import org.example.mapper.K8sNodeMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.factory.K8sJobFactory; // <-- 导入 K8sJobFactory
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map; // <-- 可能需要这个
import java.util.concurrent.ConcurrentHashMap; // <-- 可能需要这个

//@Service
public class NodeSyncService {

    private static final Logger log = LoggerFactory.getLogger(NodeSyncService.class);

    // @Autowired private KubernetesClient client; // <-- 移除这个直接注入
    private final K8sJobFactory k8sJobFactory; // <-- 注入工厂
    private final NodeManagementMapper nodeManagementMapper;
    private final K8sNodeMapper k8sNodeMapper;

    // 存储每个集群的活跃 watcher，用于重连等场景
    private final Map<String, Watcher<Node>> activeWatchers = new ConcurrentHashMap<>();


    // 构造函数调整为注入 K8sJobFactory
    @Autowired
    public NodeSyncService(K8sJobFactory k8sJobFactory,
                           NodeManagementMapper nodeManagementMapper,
                           K8sNodeMapper k8sNodeMapper) {
        this.k8sJobFactory = k8sJobFactory;
        this.nodeManagementMapper = nodeManagementMapper;
        this.k8sNodeMapper = k8sNodeMapper;
    }

    @PostConstruct
    public void init() {
        log.info("Starting initial full sync and watchers for all managed clusters...");
        // 遍历 K8sJobFactory 中所有集群客户端，为每个集群执行同步和启动 watcher
        // 由于当前是单集群部署，这里只会有一个客户端
        k8sJobFactory.getClusterClients().forEach((clusterId, client) -> {
            log.info("Processing cluster: {}", clusterId);
            initialFullSync(client, clusterId); // 为每个客户端执行全量同步
            startNodeWatcher(client, clusterId); // 为每个客户端启动 watcher
        });
        log.info("Initial full sync and watchers completed for all managed clusters.");
    }

    /**
     * 初始全量同步：获取所有节点，逐个 upsert（包含 Metrics 数据）
     * 接收 KubernetesClient 参数
     */
    private void initialFullSync(KubernetesClient client, String clusterId) { // <-- 传入 clusterId，避免 in-cluster null context
        log.debug("Performing initial full sync for cluster: {}", clusterId);
        List<Node> nodes = client.nodes().list().getItems();
        nodes.forEach(node -> syncNode(node, client, clusterId)); // 将 client 传入 syncNode
    }

    /**
     * 【新增】定时任务：每 30 秒更新一次节点的实时 Metrics
     * 遍历所有 K8sJobFactory 管理的集群
     */
    @Scheduled(fixedRate = 30000) // 每 30 秒执行一次
    public void updateNodeMetrics() {
        log.debug("定时更新所有集群的节点 Metrics...");
        k8sJobFactory.getClusterClients().forEach((clusterId, client) -> { // <-- 遍历所有客户端
            List<Node> nodes = client.nodes().list().getItems();
            nodes.forEach(node -> syncNode(node, client, clusterId)); // 将 client 传入 syncNode
        });
    }

    /**
     * Upsert 逻辑：存在则 update，否则 insert
     * ✅ 使用 toEntityWithMetrics 获取实时数据
     * 接收 KubernetesClient 参数
     */
    private void syncNode(Node k8sNode, KubernetesClient client, String clusterId) { // <-- 添加 clusterId 参数
        // ✅ 关键：使用 toEntityWithMetrics 获取包含 Metrics 的数据
        NodeManagement entity = k8sNodeMapper.toEntityWithMetrics(k8sNode, client); // 传入 client

        if (entity == null) {
            log.warn("Skipping null entity for node: {}", k8sNode.getMetadata().getName());
            return;
        }

        // 检查数据库中是否存在
        NodeManagement existing = nodeManagementMapper.getNodeByName(entity.getNodeName());
        if (existing != null) {
            // 存在：更新（保留原有 node_id）
            entity.setNodeId(existing.getNodeId());
            nodeManagementMapper.updateNodeFromK8s(entity);
            log.debug("Node '{}' updated: CPU={}/{}, Memory={}/{}GB in cluster {}",
                    entity.getNodeName(),
                    entity.getCurrentCpu(), entity.getMaxCpu(),
                    entity.getCurrentMemory(), entity.getMaxMemory(),
                    clusterId);
        } else {
            // 不存在：插入
            nodeManagementMapper.insertNode(entity);
            log.info("Node '{}' inserted into the database in cluster {}.",
                    entity.getNodeName(),
                    clusterId);
        }
    }

    /**
     * 启动 K8s Node Watcher，监听节点的增删改事件
     * 接收 KubernetesClient 和 clusterId 参数
     */
    private void startNodeWatcher(KubernetesClient client, String clusterId) { // <-- 添加 client 和 clusterId 参数
        Watcher<Node> watcher = new Watcher<Node>() {
            @Override
            public void eventReceived(Action action, Node node) {
                String nodeName = node.getMetadata().getName();
                switch (action) {
                    case ADDED:
                    case MODIFIED:
                        log.info("Node event: {} for {} in cluster {}", action, nodeName, clusterId);
                        syncNode(node, client, clusterId); // 将 client 传入 syncNode
                        break;
                    case DELETED:
                        log.info("Node deleted: {} in cluster {}", nodeName, clusterId);
                        nodeManagementMapper.deleteByName(nodeName); // 这里可能需要考虑clusterId，如果nodeName在不同集群可能重复
                        break;
                    case ERROR:
                        log.error("Error event for node: {} in cluster {}", nodeName, clusterId);
                        break;
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                log.warn("Node watcher for cluster {} closed", clusterId, cause);
                // 可添加重连逻辑，并确保传递正确的 client 和 clusterId
                try {
                    Thread.sleep(5000);
                    log.info("Attempting to restart node watcher for cluster {}...", clusterId);
                    startNodeWatcher(client, clusterId); // 重连时传入 client 和 clusterId
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Node watcher for cluster {} reconnection interrupted.", clusterId);
                }
            }
        };
        client.nodes().watch(watcher);
        activeWatchers.put(clusterId, watcher); // 保持对 watcher 的引用，以便管理
    }
}
