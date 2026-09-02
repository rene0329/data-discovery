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
        int count = tasks.countTasks();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskCount", count);
        result.put("heatEnabled", count == 0);
        result.put("aggregationEnabled", count > 0);
        result.put("heatReason", count > 0 ? "已有任务，请使用原位汇聚" : null);
        result.put("aggregationReason", count == 0 ? "暂无任务，请使用热敏存储" : null);
        return result;
    }

    public DatasetStoragePlan preview(String mode) {
        validateMode(mode);
        Map<String, Object> policy = policy();
        if (!Boolean.TRUE.equals(policy.get("heat".equals(mode) ? "heatEnabled" : "aggregationEnabled"))) {
            throw RegistrationException.conflict(String.valueOf(policy.get(
                    "heat".equals(mode) ? "heatReason" : "aggregationReason")));
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
            if (datasets.countActiveSchedulingReferences(dataset.getDatasetId()) > 0
                    || datasets.countActiveMigrationReferences(dataset.getDatasetId(), dataset.getLegacyDataId()) > 0) {
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
        DatasetStoragePlan current = preview(request.getMode());
        if (current.getAssignments().isEmpty()) throw RegistrationException.conflict("当前布局无需迁移或复制");
        if (!current.getAssignments().equals(request.getAssignments())) {
            throw RegistrationException.conflict("数据、节点或布局已变化，请重新预览后确认");
        }
        SchedulingPlanRequest plan = new SchedulingPlanRequest();
        plan.setExternalPlanId(request.getExternalPlanId());
        plan.setAssignments(current.getAssignments());
        SchedulingPlanRequest.Algorithm algorithm = new SchedulingPlanRequest.Algorithm();
        algorithm.setName("heat".equals(request.getMode()) ? "热敏存储" : "原位汇聚");
        algorithm.setVersion("2.0");
        plan.setAlgorithm(algorithm);
        return scheduling.submitDataPlan(plan);
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
