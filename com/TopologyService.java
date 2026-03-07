package com;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TopologyService类用于管理整个网络拓扑图。
 * 它能够添加、更新和查询节点及其连接信息。
 */
public class TopologyService {
    private final Map<String, Node> nodes; // 存储所有节点的映射，键是节点ID

    /**
     * 构造函数，用于初始化TopologyService对象。
     */
    public TopologyService() {
        this.nodes = new HashMap<>(); // 初始化节点映射
    }



    public TopologyService(Map<String, Node> nodes) {
        this.nodes = nodes;
    }




    // 添加节点到拓扑图中
    public void addNode(Node node) {
        nodes.put(node.getId(), node);
    }



    // 根据节点ID获取节点信息
    public Node getNode(String nodeId) {
        return nodes.get(nodeId);
    }



    // 获取所有节点的映射
    public Map<String, Node> getNodes() {
        return nodes;
    }



    // 在两个节点之间添加连接
    public void addConnection(String nodeId1, String nodeId2, double bandwidth, double latency) {
        Node node1 = nodes.get(nodeId1);
        Node node2 = nodes.get(nodeId2);

        if (node1 != null && node2 != null) {
            // 为两个节点分别添加连接信息，实现双向连接
            node1.addConnection(nodeId2, bandwidth, latency);
            node2.addConnection(nodeId1, bandwidth, latency);
        }
    }




    // 更新节点的负载信息
    public void updateNodeLoad(String nodeId, double load) {
        Node node = nodes.get(nodeId);
        if (node != null) {
            node.setLoad(load);
        }
    }




    // 获取所有存储节点
    public Map<String, Node> getStorageNodes() {
        Map<String, Node> storageNodes = new HashMap<>();
        for (Node node : nodes.values()) {
            if ("storage".equals(node.getType())) { // 假设存储节点的类型是"storage"
                storageNodes.put(node.getId(), node);
            }
        }
        return storageNodes;
    }




    // 获取数据块的存储位置
    public Node getStorageNode(DataBlock dataBlock) {
        return dataBlock.getStorageNode();
    }


    // 获取所有计算节点
    public Map<String, Node> getComputeNodes1() {
        Map<String, Node> computeNodes = new HashMap<>();
        for (Node node : nodes.values()) {
            if ("compute".equals(node.getType())) { // 假设计算节点的类型是"compute"
            computeNodes.put(node.getId(), node);
            }
        }
        return computeNodes;
    }


    // 获取所有计算节点
    public List<Node> getComputeNodes() {
        return nodes.values().stream()
            .filter(node -> "compute".equals(node.getType()))
            .collect(Collectors.toList());
    }
    



    // 设置存储节点的中心性
    public void setStorageNodeCentrality(String nodeId, double centrality) {
        Node node = nodes.get(nodeId);
        if (node != null && "storage".equals(node.getType())) { // 确保是存储节点
            node.setCentrality(centrality);
        }
    }





    // 估算训练时间，基于当前负载
    public double estimateTrainingTime(Node computeNode, DataBlock dataBlock) {
        // 这里假设训练时间与节点负载成正比
        if () {
            
        }
        return computeNode.getLoad() * dataBlock.getComputeRequirement();
    }





    //分配数据块
    public void assignDataBlocks(DataBlock[] dataBlocks) {
        Map<String, Node> storageNodes = getStorageNodes();
        
        for (DataBlock dataBlock : dataBlocks) {
            Node bestNode = null;
            double bestScore = -Double.MAX_VALUE;
            boolean allAssigned = storageNodes.values().stream().allMatch(Node::isAssigned);
            if (allAssigned) {
                // 重置所有节点的 assigned 属性
                storageNodes.values().forEach(Node::resetAssignment);
            }
            for (Node storageNode : storageNodes.values()) {
                if(!storageNode.isAssigned)
                {double score = storageNode.getCentrality() 
                    if (score > bestScore) {
                        bestScore = score;
                        bestNode = storageNode;
                    }}

            }

            if (bestNode != null) {
                bestNode.addDataBlock(dataBlock);
                System.out.println("Assigned DataBlock " + dataBlock.getId() + " to Storage Node " + bestNode.getId());
            }
        }
    }
    // 假设存在计算路径消耗的方法
    public double calculatePathCost(Node storageNode, Node computeNode,TopologyService topologyService) {
    double pathCost = 0.0;
    // 遍历连接，计算总的路径消耗（可以根据具体情况实现）
    for (Connection connection : storageNode.getConnections().values()) {
        if(computeNode.getId()==connection.getNodeId()){
            pathCost += connection.getLatency(); // 或者其他计算逻辑
            return pathCost;
        }
    }
    return topologyService.findShortestPath(storageNode,computeNode,topologyService);

}





 // 计算数据块从一个节点传输到另一个节点的时间
    public double transferData(DataBlock dataBlock, Node destination,TopologyService topologyService) {
        Node sourceNode = dataBlock.getStorageNode();
        Connection directConnection = sourceNode.getConnections().get(destination.getId());

        if (directConnection != null) {
            // 如果有直接连接，计算直接传输时间
            double dataSize = 1.0; // 数据块的大小
            double bandwidth = directConnection.getBandwidth();
            return bandwidth > 0 ? dataSize / bandwidth : Double.POSITIVE_INFINITY;
        }

        // 如果没有直接连接，寻找最近联通路径
        double shortestPathCost = findShortestPath(sourceNode, destination,topologyService);

        if (shortestPathCost < Double.POSITIVE_INFINITY) {
            // 假设最短路径的带宽等于路径上最小的带宽（即路径瓶颈）
            double dataSize = 1.0;
            return dataSize / shortestPathCost;
        }
        // 如果没有联通路径，返回一个极大的传输时间表示无法传输
        return Double.POSITIVE_INFINITY;
    }





    // 计算分片开销
    public double calculateSplittingOverhead(DataBlock dataBlock) {
        // 假设分片开销与数据块大小有关
        return dataBlock.getSize() * 0.1; // 假设每个数据块分片需要10%的开销
    }




    // 使用Dijkstra算法寻找两个节点之间的最短路径
    public double findShortestPath(Node source, Node destination,TopologyService topologyService) {
        Map<String, Double> distances = new HashMap<>();
        PriorityQueue<NodeDistancePair> queue = new PriorityQueue<>(Comparator.comparingDouble(NodeDistancePair::getDistance));

        // 初始化距离为无穷大
        for (String nodeId : getNodes().keySet()) {
            distances.put(nodeId, Double.POSITIVE_INFINITY);
        }
        distances.put(source.getId(), 0.0);

        queue.add(new NodeDistancePair(source, 0.0));

        while (!queue.isEmpty()) {
            NodeDistancePair currentPair = queue.poll();
            Node currentNode = currentPair.getNode();
            double currentDistance = currentPair.getDistance();

            if (currentDistance > distances.get(currentNode.getId())) {
                continue;
            }

            for (Map.Entry<String, Connection> entry : currentNode.getConnections().entrySet()) {
                Node neighborNode = topologyService.getNode(entry.getKey());
                double newDist = currentDistance + entry.getValue().getLatency();

                if (newDist < distances.get(neighborNode.getId())) {
                    distances.put(neighborNode.getId(), newDist);
                    queue.add(new NodeDistancePair(neighborNode, newDist));
                }
            }
        }

        return distances.get(destination.getId());
    }



    // 内部类用于保存节点及其与源节点的距离
    private static class NodeDistancePair {
        private final Node node;
        private final double distance;

        public NodeDistancePair(Node node, double distance) {
            this.node = node;
            this.distance = distance;
        }

        public Node getNode() {
            return node;
        }

        public double getDistance() {
            return distance;
        }
    }



    // 计算分片开销，只有在当前节点无法容纳全部数据块时才计算分片开销
    public double calculateSplittingOverhead(DataBlock dataBlock, Node computeNode) {
        double excessSize = dataBlock.getSize() - (computeNode.getComputeCapacity() - computeNode.getLoad());
        if (excessSize > 0) {
            return excessSize ; // 只有超过部分产生分片开销
        }
        return 0;
    }



    // // 计算两个节点之间的路径消耗（假设简单的延迟作为消耗）
    // private double calculatePathCost(Node storageNode, Node computeNode) {
    //     Connection connection = storageNode.getConnections().get(computeNode.getId());
    //     return connection != null ? connection.getLatency() : Double.MAX_VALUE;
    // }



    // 获取次优节点（路径消耗次低的节点）
    public Node getNextBestNode(Node storageNode, Node bestComputeNode) {
        Node nextBestNode = null;
        double minPathCost = Double.MAX_VALUE;

        for (Node computeNode : getComputeNodes()) {
            if (computeNode != bestComputeNode) {
                double pathCost = calculatePathCost(storageNode, computeNode);
                if (pathCost < minPathCost) {
                    minPathCost = pathCost;
                    nextBestNode = computeNode;
                }
            }
        }
        return nextBestNode;
    }



    // 估算负载恢复时间
    public double estimateLoadRecoveryTime(Node computeNode) {
        return computeNode.getLoad() * 0.5; // 假设负载恢复时间是负载的一半
    }


     // 等待负载恢复
     public void waitForLoadRecovery(Node computeNode) {
        // 简单的模拟：等待负载恢复
        computeNode.setLoad(computeNode.getLoad() - estimateLoadRecoveryTime(computeNode));
    }


    // 分片并分配数据块
    public void splitAndAssignDataBlock(DataBlock dataBlock, List<Node> computeNodes) {
        // 简单的分片示例
        DataBlock part1 = new DataBlock(dataBlock.getId() + "_part1");
        DataBlock part2 = new DataBlock(dataBlock.getId() + "_part2");


        computeNodes.get(0).addDataBlock(part1);
        computeNodes.get(1).addDataBlock(part2);
    }



    // 将计算任务分配给次优节点
    public void assignComputationToNextBestNode(DataBlock dataBlock, Node nextBestNode) {
        nextBestNode.addDataBlock(dataBlock);
    }



    // 分配计算任务到最佳计算节点
    public void assignComputation(DataBlock dataBlock, Node bestComputeNode) {
        bestComputeNode.addDataBlock(dataBlock);
    }



    // 更新计算节点的负载信息
    public void updateComputeNodeLoad(Node computeNode, DataBlock dataBlock) {
        computeNode.setLoad(computeNode.getLoad() + dataBlock.getComputeRequirement());
    }



    public void T2Scenario(DataBlock[] dataBlocks) {
        double T2DataMovementTime = 0;

        for (DataBlock dataBlock : dataBlocks) {
            Node storageNode = getStorageNode(dataBlock);
            Node bestComputeNode = null;
            Node nextBestNode = null;
            double minTotalCost = Double.MAX_VALUE;

            for (Node computeNode : getComputeNodes()) {
                double loadAdjustedTime = estimateTrainingTime(computeNode, dataBlock);
                double pathCost = calculatePathCost(storageNode, computeNode);
                double totalCost = pathCost + loadAdjustedTime;

                if (computeNode.getLoad() < computeNode.getComputeCapacity()) {
                    if (totalCost < minTotalCost) {
                        
                        minTotalCost = totalCost; 
                        nextBestNode=bestComputeNode;
                        bestComputeNode = computeNode;
                    }
                }
            }

            if (bestComputeNode == null) {
                double SO = calculateSplittingOverhead(dataBlock);
                //次优节点寻找还没定义
                double APC = calculateAdditionalPathCost(storageNode, nextBestNode);
                double LRT = estimateLoadRecoveryTime(nextBestNode);

                if (LRT < Math.min(SO, APC)) {
                    waitForLoadRecovery(nextBestNode);
                } else if (SO < APC && LRT > SO) {
                    splitAndAssignDataBlock(dataBlock, getComputeNodes());
                } else if (APC < SO && LRT > APC) {
                    assignComputationToNextBestNode(dataBlock, nextBestNode);
                }
            } else {
                assignComputation(dataBlock, bestComputeNode);
            }

            updateComputeNodeLoad(bestComputeNode, dataBlock);

            if (dataBlock.getStorageNode() != bestComputeNode) {
                T2DataMovementTime += transferData(dataBlock, bestComputeNode);
            }
        }

        // Node centralComputeNode = getNextBestNode(); // 假设你有一个中心计算节点
        // for (Node computeNode : getComputeNodes()) {
        //     if (computeNode != centralComputeNode) {
        //         T2DataMovementTime += transferParameters(computeNode, centralComputeNode);
        //     }
        // }

        System.out.println("T2DataMovementTime: " + T2DataMovementTime);


        
    }
    
}