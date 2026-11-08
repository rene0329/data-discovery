package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.DurableTaskJob;
import org.example.service.InPlacePlacementService.Placement;
import org.example.service.NetworkTopologyService.NetworkPath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Logs the actual admission snapshot without changing candidate order or resource allocation. */
@Slf4j
final class SchedulingCandidateLogger {
    private final Map<Long, List<List<Object>>> lastDecisions = new HashMap<>();

    void retainJobs(Set<Long> activeJobIds) { lastDecisions.keySet().retainAll(activeJobIds); }

    void report(DurableTaskJob row, List<Placement> candidates, NodeResourceLedger ledger,
                JobResourceDemand demand, Placement preferred) {
        List<List<Object>> decision = new ArrayList<>();
        decision.add(Arrays.asList(row.getAttempt(), row.getExecutionMode(), demand.getCpu(),
                demand.getMemoryGi(), demand.getGpu(), demand.getEphemeralStorageGi()));
        for (Placement candidate : candidates) {
            NetworkPath path = candidate.getPath();
            decision.add(Arrays.asList(candidate.getReplica().getReplicaId(),
                    NodeResourceLedger.key(candidate.getReplicaNode()), NodeResourceLedger.key(candidate.getComputeNode()),
                    candidate.getTier(), path == null ? null : path.getLatencyMs(),
                    path == null ? null : path.getBandwidthMbps(), path == null ? null : path.getNodeIds(),
                    outcome(candidate, ledger, demand, preferred), shortages(candidate, ledger, demand)));
        }
        // Free quantities can fluctuate every poll. Repeat only when candidates, measurements,
        // requests, or their eligibility/priority outcome actually changes.
        if (decision.equals(lastDecisions.put(row.getId(), decision))) return;
        log.info("【调度候选汇总】taskId={} datasetId={} mode={} computeJobId={} candidateCount={} cpuRequested={} memoryRequestedGi={} gpuRequested={} ephemeralStorageRequestedGi={}",
                row.getTaskId(), row.getDatasetId(), row.getExecutionMode(), row.getId(), candidates.size(),
                demand.getCpu(), demand.getMemoryGi(), demand.getGpu(), demand.getEphemeralStorageGi());
        for (int i = 0; i < candidates.size(); i++) {
            Placement candidate = candidates.get(i);
            NetworkPath path = candidate.getPath();
            String key = NodeResourceLedger.key(candidate.getComputeNode());
            boolean local = candidate.getReplicaNode().getNodeId().equals(candidate.getComputeNode().getNodeId());
            String result = outcome(candidate, ledger, demand, preferred);
            String reason = "RESOURCE_UNKNOWN".equals(result) ? "节点资源信息暂不可用"
                    : "RESOURCE_INSUFFICIENT".equals(result) ? shortages(candidate, ledger, demand).toString()
                    : "PREFERRED".equals(result) ? "资源满足且候选顺序优先，等待业务条件及提交确认"
                    : "资源满足，但候选顺序靠后";
            log.info("【调度候选】taskId={} datasetId={} mode={} computeJobId={} rank={} replicaId={} sourceNode={} targetNode={} cluster={} tier={} latencyMs={} bandwidthMbps={} pathNodeIds={} cpuFree={} memoryFreeGi={} gpuFree={} ephemeralStorageFreeGi={} podSlotsFree={} outcome={} reason={}",
                    row.getTaskId(), row.getDatasetId(), row.getExecutionMode(), row.getId(), i + 1,
                    candidate.getReplica().getReplicaId(), candidate.getReplicaNode().getNodeName(),
                    candidate.getComputeNode().getNodeName(), candidate.getComputeNode().getCluster(), candidate.getTier(),
                    path == null ? (local ? "0.0" : "未测量") : path.getLatencyMs(),
                    path == null ? (local ? "本地读取，无网络传输" : "未测量") : path.getBandwidthMbps(),
                    path == null ? null : path.getNodeIds(), ledger.availableCpu(key), ledger.availableMemoryGi(key),
                    ledger.availableGpu(key), ledger.availableEphemeralStorageGi(key), ledger.availablePodSlots(key), result, reason);
        }
    }

    private String outcome(Placement candidate, NodeResourceLedger ledger, JobResourceDemand demand, Placement preferred) {
        if (ledger.availableCpu(NodeResourceLedger.key(candidate.getComputeNode())) == null) return "RESOURCE_UNKNOWN";
        if (!ledger.fits(candidate.getComputeNode(), demand)) return "RESOURCE_INSUFFICIENT";
        return candidate == preferred ? "PREFERRED" : "LOWER_PRIORITY";
    }

    private List<String> shortages(Placement candidate, NodeResourceLedger ledger, JobResourceDemand demand) {
        List<String> result = new ArrayList<>();
        String key = NodeResourceLedger.key(candidate.getComputeNode());
        if (ledger.availableCpu(key) == null) return result;
        if (ledger.availableCpu(key) + 1e-9 < demand.getCpu()) result.add("CPU不足");
        if (ledger.availableMemoryGi(key) + 1e-9 < demand.getMemoryGi()) result.add("内存不足");
        if (ledger.availableGpu(key) + 1e-9 < demand.getGpu()) result.add("GPU不足");
        if (ledger.availableEphemeralStorageGi(key) + 1e-9 < demand.getEphemeralStorageGi()) result.add("临时存储不足");
        if (ledger.availablePodSlots(key) < 1) result.add("Pod数量已达上限");
        return result;
    }
}
