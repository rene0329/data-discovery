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
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** The original heat/capacity/proximity policy, applied to logical datasets and real replicas. */
@Service
public class DatasetStorageService {
    private final DatasetRegistrationMapper datasets;
    private final NodeManagementMapper nodes;
    private final TaskManagementMapper tasks;
    private final NodeAvailabilityService nodeAvailability;
    private final DatasetReplicaAvailabilityService replicaAvailability;
    private final NetworkTopologyService topology;
    private final SchedulingService scheduling;

    public DatasetStorageService(DatasetRegistrationMapper datasets, NodeManagementMapper nodes,
            TaskManagementMapper tasks, NodeAvailabilityService nodeAvailability,
            DatasetReplicaAvailabilityService replicaAvailability, NetworkTopologyService topology,
            SchedulingService scheduling) {
        this.datasets = datasets;
        this.nodes = nodes;
        this.tasks = tasks;
        this.nodeAvailability = nodeAvailability;
        this.replicaAvailability = replicaAvailability;
        this.topology = topology;
        this.scheduling = scheduling;
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
        List<NodeManagement> availableNodes = nodes.selectAllNodes().stream()
                .filter(nodeAvailability::isSchedulable).sorted(Comparator.comparing(NodeManagement::getNodeId))
                .collect(Collectors.toList());
        Map<Integer, NodeManagement> byId = availableNodes.stream()
                .collect(Collectors.toMap(NodeManagement::getNodeId, node -> node));
        List<NodeManagement> storage = availableNodes.stream().filter(DatasetSchedulingExecutor::isStorageNode)
                .collect(Collectors.toList());
        if (storage.isEmpty()) throw RegistrationException.conflict("没有可用的存储节点");

        Map<Long, List<DatasetReplica>> replicas = new HashMap<>();
        Map<Integer, Integer> used = new HashMap<>();
        Map<Integer, Double> heatLoad = new HashMap<>();
        List<RegisteredDataset> all = datasets.listDatasets(null, null);
        for (RegisteredDataset dataset : all) {
            List<DatasetReplica> copies = datasets.listReplicas(dataset.getDatasetId());
            replicas.put(dataset.getDatasetId(), copies);
            for (DatasetReplica copy : copies) {
                if ("MISSING".equals(copy.getAvailability())) continue;
                used.merge(copy.getNodeId(), 1, Integer::sum);
                heatLoad.merge(copy.getNodeId(), heat(dataset), Double::sum);
            }
        }
        storage.forEach(node -> used.merge(node.getNodeId(),
                datasets.countReservedStorageSlots(node.getNodeId()), Integer::sum));
        List<RegisteredDataset> active = all.stream().filter(d -> "ACTIVE".equals(d.getStatus()))
                .sorted(Comparator.comparingDouble(DatasetStorageService::heat).reversed()
                        .thenComparing(RegisteredDataset::getDatasetId)).collect(Collectors.toList());
        double totalHeat = Math.max(1, active.stream().mapToDouble(DatasetStorageService::heat).sum());
        Map<Integer, Double> proximity = proximity(storage, availableNodes);
        DatasetStoragePlan result = new DatasetStoragePlan();
        result.setMode(mode);
        result.setDatasetCount(active.size());
        for (int index = 0; index < active.size(); index++) {
            RegisteredDataset dataset = active.get(index);
            if (DatasetOperationGuard.busy(datasets, dataset)) {
                result.getNotices().add(dataset.getName() + "：有未完成的调度，跳过");
                continue;
            }
            List<DatasetReplica> usable = replicas.get(dataset.getDatasetId()).stream()
                    .filter(r -> byId.containsKey(r.getNodeId()) && replicaAvailability.evaluate(r).isUsable())
                    .sorted(Comparator.comparing(DatasetReplica::getReplicaId)).collect(Collectors.toList());
            if (usable.isEmpty()) {
                result.getNotices().add(dataset.getName() + "：没有可用源副本，跳过");
                continue;
            }
            DatasetReplica source = usable.get(0);
            Set<Integer> occupied = usable.stream().map(DatasetReplica::getNodeId).collect(Collectors.toSet());
            Map<Integer, NetworkTopologyService.NetworkPath> paths = topology.pathsFrom(source.getNodeId());
            NodeManagement best = choose(storage, source, occupied, paths, used, heatLoad, totalHeat, proximity, true);
            if (best == null) {
                result.getNotices().add(dataset.getName() + "：无可用路径或存储容量，跳过");
                continue;
            }
            // Reserve targets conservatively: do not spend space expected to be freed by a preceding move.
            if (!best.getNodeId().equals(source.getNodeId())) reserve(best, dataset, used, heatLoad);
            // The hottest half retain/create a real backup. Copy it before moving the source.
            if (index < active.size() / 2 && occupied.size() < 2) {
                Set<Integer> excluded = new HashSet<>(occupied);
                excluded.add(best.getNodeId());
                NodeManagement backup = choose(storage, source, excluded, paths, used, heatLoad,
                        totalHeat, proximity, false);
                if (backup != null) {
                    add(result, dataset, source, byId.get(source.getNodeId()), backup, "COPY");
                    reserve(backup, dataset, used, heatLoad);
                } else result.getNotices().add(dataset.getName() + "：没有额外备份容量，保留现有副本");
            }
            if (!best.getNodeId().equals(source.getNodeId())) {
                add(result, dataset, source, byId.get(source.getNodeId()), best, "MOVE");
            }
        }
        return result;
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
                add(result, dataset, source, byId.get(source.getNodeId()), target, "COPY");
                used.merge(target.getNodeId(), 1, Integer::sum);
                placed = true;
                break;
            }
            if (!placed) result.getNotices().add(dataset.getName() + "：无可用源副本、路径或邻近存储容量，跳过");
        }
        return result;
    }

    private NodeManagement choose(List<NodeManagement> storage, DatasetReplica source, Set<Integer> occupied,
            Map<Integer, NetworkTopologyService.NetworkPath> paths, Map<Integer, Integer> used,
            Map<Integer, Double> heatLoad, double totalHeat, Map<Integer, Double> proximity, boolean allowSource) {
        NodeManagement best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (NodeManagement node : storage) {
            boolean same = node.getNodeId().equals(source.getNodeId());
            if (!paths.containsKey(node.getNodeId()) || (occupied.contains(node.getNodeId()) && !(same && allowSource))) continue;
            int capacity = node.getNumDataset() == null ? 0 : node.getNumDataset();
            int count = used.getOrDefault(node.getNodeId(), 0);
            if (!(same && allowSource) && (capacity <= 0 || count >= capacity)) continue;
            DatasetReplica existing = datasets.findReplicaByNodePath(node.getNodeId(), source.getFilePath());
            if (existing != null && !existing.getDatasetId().equals(source.getDatasetId())) continue;
            double score = 0.4 * (capacity > 0 ? (double) (capacity - count) / capacity : 0)
                    - 0.4 * heatLoad.getOrDefault(node.getNodeId(), 0.0) / totalHeat
                    + 0.2 * proximity.getOrDefault(node.getNodeId(), 0.0)
                    + ("compute-storage".equalsIgnoreCase(node.getType()) ? 0.3 : 0);
            // Prefer keeping the source on a tie, avoiding gratuitous transfers.
            if (score > bestScore || (score == bestScore && same && allowSource)) {
                best = node;
                bestScore = score;
            }
        }
        return best;
    }

    private Map<Integer, Double> proximity(List<NodeManagement> storage, List<NodeManagement> all) {
        Set<Integer> compute = all.stream().filter(n -> "compute".equalsIgnoreCase(n.getType())
                || "compute-storage".equalsIgnoreCase(n.getType())).map(NodeManagement::getNodeId).collect(Collectors.toSet());
        Map<Integer, Set<Integer>> adjacent = new HashMap<>();
        topology.links().stream().filter(e -> "active".equalsIgnoreCase(e.getStatus()) || "UP".equalsIgnoreCase(e.getStatus()))
                .forEach(e -> {
                    adjacent.computeIfAbsent(e.getSourceId(), k -> new HashSet<>()).add(e.getTargetId());
                    adjacent.computeIfAbsent(e.getTargetId(), k -> new HashSet<>()).add(e.getSourceId());
                });
        Map<Integer, Double> scores = new HashMap<>();
        for (NodeManagement node : storage) {
            long near = adjacent.getOrDefault(node.getNodeId(), Collections.emptySet()).stream().filter(compute::contains).count();
            scores.put(node.getNodeId(), compute.isEmpty() ? 0 : (double) near / compute.size());
        }
        return scores;
    }

    private void reserve(NodeManagement node, RegisteredDataset dataset, Map<Integer, Integer> used, Map<Integer, Double> load) {
        used.merge(node.getNodeId(), 1, Integer::sum);
        load.merge(node.getNodeId(), heat(dataset), Double::sum);
    }

    private void add(DatasetStoragePlan result, RegisteredDataset dataset, DatasetReplica source,
            NodeManagement from, NodeManagement target, String action) {
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
        result.getPlacements().add(row);
    }

    private static double heat(RegisteredDataset dataset) {
        return dataset.getDataHeat() == null ? 10 : dataset.getDataHeat();
    }

    private void validateMode(String mode) {
        if (!"heat".equals(mode) && !"aggregation".equals(mode)) throw RegistrationException.invalid("unsupported storage mode");
    }
}
