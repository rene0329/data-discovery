package org.example.service;

import org.example.dto.scheduling.DatasetStoragePlan;
import org.example.dto.scheduling.SchedulingPlanRequest;
import org.example.dto.scheduling.SchedulingPlanAccepted;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.TaskManagementMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 热敏存储：热度达到阈值的数据集，迁到离它的就近计算节点最近的纯存储节点。
 * 原位汇聚：把所选数据复制到所选计算节点附近。
 */
@Service
public class DatasetStorageService {
    private final DatasetRegistrationMapper datasets;
    private final NodeManagementMapper nodes;
    private final TaskManagementMapper tasks;
    private final NodeAvailabilityService nodeAvailability;
    private final DatasetReplicaAvailabilityService replicaAvailability;
    private final NetworkTopologyService topology;
    private final SchedulingService scheduling;
    private final InPlacePlacementService inPlacePlacement;
    private final double hotThreshold;
    private final double minLatencyGainMs;

    public DatasetStorageService(DatasetRegistrationMapper datasets, NodeManagementMapper nodes,
            TaskManagementMapper tasks, NodeAvailabilityService nodeAvailability,
            DatasetReplicaAvailabilityService replicaAvailability, NetworkTopologyService topology,
            SchedulingService scheduling, InPlacePlacementService inPlacePlacement,
            @Value("${app.heat-placement.hot-threshold:30}") double hotThreshold,
            @Value("${app.heat-placement.min-latency-gain-ms:1}") double minLatencyGainMs) {
        if (!Double.isFinite(hotThreshold) || !Double.isFinite(minLatencyGainMs) || minLatencyGainMs < 0) {
            throw new IllegalArgumentException("invalid heat placement configuration");
        }
        this.datasets = datasets;
        this.nodes = nodes;
        this.tasks = tasks;
        this.nodeAvailability = nodeAvailability;
        this.replicaAvailability = replicaAvailability;
        this.topology = topology;
        this.scheduling = scheduling;
        this.inPlacePlacement = inPlacePlacement;
        this.hotThreshold = hotThreshold;
        this.minLatencyGainMs = minLatencyGainMs;
    }

    public Map<String, Object> policy() {
        int count = tasks.countUnfinishedTasks();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskCount", count);
        result.put("unfinishedTaskCount", count);
        result.put("heatEnabled", true);
        result.put("aggregationEnabled", true);
        result.put("heatReason", null);
        result.put("aggregationReason", null);
        result.put("heatThreshold", hotThreshold);
        result.put("minLatencyGainMs", minLatencyGainMs);
        return result;
    }

    public DatasetStoragePlan preview(String mode) {
        return preview(mode, null, null);
    }

    public DatasetStoragePlan preview(String mode, List<Long> datasetIds, Integer targetNodeId) {
        validateMode(mode);
        if ("aggregation".equals(mode)) return previewAggregation(datasetIds, targetNodeId);
        if (targetNodeId != null || (datasetIds != null && !datasetIds.isEmpty())) {
            throw RegistrationException.invalid("热敏存储作用于全部空闲数据，不接受汇聚目标参数");
        }
        return previewHeat();
    }

    /**
     * 只处理热度达到阈值的数据集，只做 MOVE（有效副本数不变）：
     * 1. 用就近计算调度算法（InPlacePlacementService）得到副本和它的计算节点 C；
     * 2. 在"副本节点 → C"这条路径上找离 C 最近、还有容量的纯存储节点（不含计算存储节点）；
     * 3. 只有到 C 的时延至少缩短 minLatencyGainMs 才迁移，数据只会沿着通往计算节点的路径靠近。
     */
    private DatasetStoragePlan previewHeat() {
        Map<Integer, NodeManagement> byId = nodes.selectAllNodes().stream()
                .filter(nodeAvailability::isSchedulable)
                .collect(Collectors.toMap(NodeManagement::getNodeId, node -> node));
        List<RegisteredDataset> active = datasets.listDatasets(null, null).stream()
                .filter(d -> "ACTIVE".equals(d.getStatus()))
                .sorted(Comparator.comparingDouble(DatasetStorageService::heat).reversed()
                        .thenComparing(RegisteredDataset::getDatasetId)).collect(Collectors.toList());
        Map<Integer, Integer> used = new HashMap<>();
        DatasetStoragePlan result = new DatasetStoragePlan();
        result.setMode("heat");
        result.setDatasetCount(active.size());
        int cold = 0;
        for (RegisteredDataset dataset : active) {
            double heat = heat(dataset);
            if (heat < hotThreshold) {
                cold++;
                continue;
            }
            String label = dataset.getName() + "（热度 " + format(heat) + "）";
            if (DatasetOperationGuard.busy(datasets, dataset)) {
                result.getNotices().add(label + "：有进行中的任务或调度，待其结束后再迁移");
                continue;
            }
            InPlacePlacementService.Placement placement = inPlacePlacement.place(datasets.listReplicas(dataset.getDatasetId()));
            if (!placement.isFound()) {
                result.getNotices().add(label + "：没有可用副本或可到达的计算节点，保持不动");
                continue;
            }
            DatasetReplica source = placement.getReplica();
            NodeManagement from = placement.getReplicaNode();
            NodeManagement compute = placement.getComputeNode();
            NetworkTopologyService.NetworkPath route = topology.pathsFrom(from.getNodeId()).get(compute.getNodeId());
            if (route == null) {
                result.getNotices().add(label + "：逻辑拓扑中没有 " + from.getNodeName() + " 到计算节点 "
                        + compute.getNodeName() + " 的路径，保持不动");
                continue;
            }
            Map<Integer, NetworkTopologyService.NetworkPath> toCompute = topology.pathsFrom(compute.getNodeId());
            NodeManagement target = null;
            NetworkTopologyService.NetworkPath targetPath = null;
            NodeManagement nearerCopy = null;
            NodeManagement full = null;
            for (Integer nodeId : route.getNodeIds()) {
                NodeManagement node = byId.get(nodeId);
                NetworkTopologyService.NetworkPath path = toCompute.get(nodeId);
                if (nodeId.equals(from.getNodeId()) || node == null || path == null
                        || !"storage".equalsIgnoreCase(node.getType())) continue;
                DatasetReplica atTarget = datasets.findReplicaByNodePath(nodeId, source.getFilePath());
                if (atTarget != null && !atTarget.getDatasetId().equals(dataset.getDatasetId())) continue;
                if (atTarget != null && !"MISSING".equals(atTarget.getAvailability())) {
                    nearerCopy = node;
                    continue;
                }
                if (!hasRoom(node, used)) {
                    full = node;
                    continue;
                }
                if (targetPath == null || path.getLatencyMs() < targetPath.getLatencyMs()
                        || (path.getLatencyMs() == targetPath.getLatencyMs()
                            && path.getBandwidthMbps() > targetPath.getBandwidthMbps())) {
                    target = node;
                    targetPath = path;
                }
            }
            if (nearerCopy != null) {
                result.getNotices().add(label + "：" + nearerCopy.getNodeName() + " 上已有这个数据集更靠近计算节点 "
                        + compute.getNodeName() + " 的副本，不再迁移");
                continue;
            }
            String current = from.getNodeName() + " → " + compute.getNodeName() + " " + format(route.getLatencyMs()) + " ms";
            if (target == null && full != null) {
                result.getNotices().add(label + "：更靠近计算节点 " + compute.getNodeName() + " 的存储节点 "
                        + full.getNodeName() + " 容量已满，保持在 " + from.getNodeName());
                continue;
            }
            if (target == null) {
                result.getNotices().add(label + "：" + from.getNodeName() + " 已是通往就近计算节点 "
                        + compute.getNodeName() + " 路径上可用的最近存储节点（" + current + "），无需迁移");
                continue;
            }
            double gain = route.getLatencyMs() - targetPath.getLatencyMs();
            if (gain < minLatencyGainMs) {
                result.getNotices().add(label + "：迁到 " + target.getNodeName() + " 只缩短 " + format(gain)
                        + " ms（低于 " + format(minLatencyGainMs) + " ms），保持在 " + from.getNodeName());
                continue;
            }
            add(result, dataset, source, from, target, "MOVE", compute.getNodeId(),
                    "热度 " + format(heat) + " ≥ " + format(hotThreshold) + "；就近计算节点 " + compute.getNodeName()
                            + "；到计算节点时延 " + format(route.getLatencyMs()) + " → "
                            + format(targetPath.getLatencyMs()) + " ms");
            used.merge(target.getNodeId(), 1, Integer::sum);
        }
        if (cold > 0) {
            result.getNotices().add(cold + " 个数据集热度低于阈值 " + format(hotThreshold) + "，保持不动");
        }
        return result;
    }

    private boolean hasRoom(NodeManagement node, Map<Integer, Integer> used) {
        int count = used.computeIfAbsent(node.getNodeId(), id -> datasets.countStorageSlots(id)
                + datasets.countReservedStorageSlots(id));
        return node.getNumDataset() != null && count < node.getNumDataset();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("\\.?0+$", "");
    }

    public SchedulingPlanAccepted submit(DatasetStoragePlan.Submit request) {
        if (request == null || request.getExternalPlanId() == null || request.getExternalPlanId().trim().isEmpty()) {
            throw RegistrationException.invalid("externalPlanId is required");
        }
        DatasetStoragePlan current = preview(request.getMode(), request.getDatasetIds(), request.getTargetNodeId());
        if (current.getAssignments().isEmpty()) throw RegistrationException.conflict("当前布局无需迁移或复制");
        if (!current.getAssignments().equals(request.getAssignments())) {
            throw RegistrationException.conflict("数据、节点或布局已变化，请重新预览后确认");
        }
        SchedulingPlanRequest plan = new SchedulingPlanRequest();
        plan.setExternalPlanId(request.getExternalPlanId());
        plan.setAssignments(current.getAssignments());
        SchedulingPlanRequest.Algorithm algorithm = new SchedulingPlanRequest.Algorithm();
        algorithm.setName("heat".equals(request.getMode()) ? "热敏存储" : "原位汇聚");
        algorithm.setVersion("3.0");
        plan.setAlgorithm(algorithm);
        return scheduling.submitDataPlan(plan);
    }

    /** Prepare only requested data near a chosen compute node. Never delete a source. */
    private DatasetStoragePlan previewAggregation(List<Long> datasetIds, Integer targetNodeId) {
        if (datasetIds == null || datasetIds.isEmpty() || datasetIds.contains(null)
                || new HashSet<>(datasetIds).size() != datasetIds.size() || targetNodeId == null) {
            throw RegistrationException.invalid("原位汇聚需要选择不重复的数据集和目标计算节点");
        }
        List<NodeManagement> available = nodes.selectAllNodes().stream()
                .filter(nodeAvailability::isSchedulable).collect(Collectors.toList());
        Map<Integer, NodeManagement> byId = available.stream()
                .collect(Collectors.toMap(NodeManagement::getNodeId, n -> n));
        NodeManagement compute = byId.get(targetNodeId);
        if (compute == null || !("compute".equalsIgnoreCase(compute.getType())
                || "compute-storage".equalsIgnoreCase(compute.getType()))) {
            throw RegistrationException.conflict("请选择可用的计算或计算存储节点");
        }
        Map<Integer, NetworkTopologyService.NetworkPath> toCompute = topology.pathsFrom(targetNodeId);
        List<NodeManagement> targets = available.stream().filter(DatasetSchedulingExecutor::isStorageNode)
                .filter(n -> toCompute.containsKey(n.getNodeId()))
                .sorted(Comparator.comparingInt((NodeManagement n) -> n.getNodeId().equals(targetNodeId) ? 0 : 1)
                        .thenComparingDouble(n -> toCompute.get(n.getNodeId()).getLatencyMs())
                        .thenComparing(NodeManagement::getNodeId)).collect(Collectors.toList());
        Map<Integer, Map<Integer, NetworkTopologyService.NetworkPath>> sourcePaths = new HashMap<>();
        Map<Integer, Integer> used = new HashMap<>();
        targets.forEach(n -> used.put(n.getNodeId(), datasets.countStorageSlots(n.getNodeId())
                + datasets.countReservedStorageSlots(n.getNodeId())));
        DatasetStoragePlan result = new DatasetStoragePlan();
        result.setMode("aggregation");
        result.setDatasetIds(new ArrayList<>(datasetIds));
        result.setTargetNodeId(targetNodeId);
        result.setDatasetCount(datasetIds.size());
        for (Long id : new TreeSet<>(datasetIds)) {
            RegisteredDataset dataset = datasets.findDatasetById(id);
            if (dataset == null || !"ACTIVE".equals(dataset.getStatus())) {
                throw RegistrationException.conflict("数据集未激活或不存在：" + id);
            }
            List<DatasetReplica> usable = datasets.listReplicas(id).stream()
                    .filter(r -> byId.containsKey(r.getNodeId()) && replicaAvailability.evaluate(r).isUsable())
                    .sorted(Comparator.comparing(DatasetReplica::getReplicaId)).collect(Collectors.toList());
            if (DatasetOperationGuard.busy(datasets, dataset)) {
                result.getNotices().add(dataset.getName() + "：有未完成的任务或调度，保留源副本并跳过");
                continue;
            }
            if (usable.stream().anyMatch(r -> targetNodeId.equals(r.getNodeId()))) {
                result.getNotices().add(dataset.getName() + "：目标计算节点已有可用副本，直接复用");
                continue;
            }
            boolean placed = false;
            for (NodeManagement target : targets) {
                if (usable.stream().anyMatch(r -> target.getNodeId().equals(r.getNodeId()))) {
                    result.getNotices().add(dataset.getName() + "：复用邻近存储节点 " + target.getNodeName() + " 的副本");
                    placed = true;
                    break;
                }
                if (target.getNumDataset() == null || used.get(target.getNodeId()) >= target.getNumDataset()) continue;
                DatasetReplica source = usable.stream().filter(r -> {
                    DatasetReplica existing = datasets.findReplicaByNodePath(target.getNodeId(), r.getFilePath());
                    return (existing == null || existing.getDatasetId().equals(id))
                            && sourcePaths.computeIfAbsent(r.getNodeId(), topology::pathsFrom).containsKey(target.getNodeId());
                }).min(Comparator.comparingDouble((DatasetReplica r) ->
                        sourcePaths.get(r.getNodeId()).get(target.getNodeId()).getLatencyMs())
                        .thenComparing(DatasetReplica::getReplicaId)).orElse(null);
                if (source == null) continue;
                if (liveReplicas(id) >= SchedulingService.MAX_LIVE_REPLICAS) {
                    result.getNotices().add(dataset.getName() + "：已有 " + SchedulingService.MAX_LIVE_REPLICAS
                            + " 个有效副本（上限），不再复制");
                    placed = true;
                    break;
                }
                add(result, dataset, source, byId.get(source.getNodeId()), target, "COPY");
                used.merge(target.getNodeId(), 1, Integer::sum);
                placed = true;
                break;
            }
            if (!placed) result.getNotices().add(dataset.getName() + "：无可用源副本、路径或邻近存储容量，跳过");
        }
        return result;
    }

    private void add(DatasetStoragePlan result, RegisteredDataset dataset, DatasetReplica source,
            NodeManagement from, NodeManagement target, String action) {
        add(result, dataset, source, from, target, action, null, null);
    }

    private void add(DatasetStoragePlan result, RegisteredDataset dataset, DatasetReplica source,
            NodeManagement from, NodeManagement target, String action, Integer consumerNodeId, String reason) {
        SchedulingPlanRequest.Assignment assignment = new SchedulingPlanRequest.Assignment();
        assignment.setDatasetId(dataset.getDatasetId());
        assignment.setReplicaId(source.getReplicaId());
        assignment.setSourceNodeId(source.getNodeId());
        assignment.setTargetNodeId(target.getNodeId());
        assignment.setAction(action);
        result.getAssignments().add(assignment);
        DatasetStoragePlan.Placement row = new DatasetStoragePlan.Placement();
        row.setDatasetId(dataset.getDatasetId());
        row.setDatasetName(dataset.getName());
        row.setDataHeat(dataset.getDataHeat());
        row.setSourceNode(from.getNodeName());
        row.setTargetNode(target.getNodeName());
        row.setAction(action);
        row.setConsumerNodeId(consumerNodeId);
        row.setReason(reason);
        result.getPlacements().add(row);
    }

    private int liveReplicas(Long datasetId) {
        return (int) datasets.listReplicas(datasetId).stream()
                .filter(replica -> !"MISSING".equals(replica.getAvailability())).count();
    }

    private static double heat(RegisteredDataset dataset) {
        return dataset.getDataHeat() == null ? 10 : dataset.getDataHeat();
    }

    private void validateMode(String mode) {
        if (!"heat".equals(mode) && !"aggregation".equals(mode)) throw RegistrationException.invalid("unsupported storage mode");
    }
}
