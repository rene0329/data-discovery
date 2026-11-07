package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.*;
import org.example.exception.RegistrationException;
import org.example.factory.K8sJobFactory;
import org.example.mapper.*;
import org.example.dto.registration.ResourceRequirements;
import org.example.vo.DataItemResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/** Persistent compute admission. The database lock serializes decisions, Kubernetes owns bound resources. */
@Service
@Slf4j
public class DurableTaskSchedulingService {
    private final DurableTaskJobMapper jobs;
    private final TaskManagementMapper tasks;
    private final DatasetRegistrationMapper datasets;
    private final NodeManagementMapper nodes;
    private final SchedulingPlanMapper plans;
    private final InPlacePlacementService placement;
    private final NodeResourceService resources;
    private final K8sJobFactory factory;
    private final ObjectMapper json;
    private final DataSource dataSource;
    private final ObjectProvider<K8sTaskOrchestratorService> orchestrator;
    private final ObjectProvider<ExternalDurableJobHandler> external;
    @org.springframework.beans.factory.annotation.Autowired private RuntimeImageMapper images;
    @org.springframework.beans.factory.annotation.Autowired private MigrationTaskMapper migrations;
    @org.springframework.beans.factory.annotation.Autowired private DatasetReplicaAvailabilityService replicaAvailability;
    @Value("${app.resource-control.max-active-jobs-per-task:4}") private int maxPerTask = 4;
    @Value("${app.resource-control.wait-timeout-minutes:1440}") private long waitTimeout = 1440;
    @Value("${dispatch.central-node.name:}") private String centralNode;

    public DurableTaskSchedulingService(DurableTaskJobMapper jobs, TaskManagementMapper tasks,
            DatasetRegistrationMapper datasets, NodeManagementMapper nodes, SchedulingPlanMapper plans,
            InPlacePlacementService placement, NodeResourceService resources, K8sJobFactory factory,
            ObjectMapper json, DataSource dataSource, ObjectProvider<K8sTaskOrchestratorService> orchestrator,
            ObjectProvider<ExternalDurableJobHandler> external) {
        this.jobs=jobs; this.tasks=tasks; this.datasets=datasets; this.nodes=nodes; this.plans=plans;
        this.placement=placement; this.resources=resources; this.factory=factory; this.json=json;
        this.dataSource=dataSource; this.orchestrator=orchestrator; this.external=external;
    }
    static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    public List<DurableTaskJob> listJobs(Integer taskId) { return jobs.listByTask(taskId); }
    public boolean owns(Integer taskId) { return jobs.countByTask(taskId)>0; }
    public void save(DurableTaskJob job) { jobs.update(job); }
    public void markExternalPrepared(DurableTaskJob job) { jobs.markExternalPrepared(job.getId()); }
    @org.springframework.transaction.annotation.Transactional
    public void ensureMigration(DurableTaskJob row,DataManagement input,NodeManagement source) {
        if(row.getMigrationId()!=null) return;
        NodeManagement target=nodes.getNodeByName(row.getTargetNode());
        MigrationTask migration=MigrationTask.builder().taskId(row.getTaskId()).dataId(input.getDataId())
                .registeredDatasetId(row.getDatasetId()).sourceNodeId(source.getNodeId())
                .targetNodeId(target.getNodeId()).status("COPYING").retryCount(row.getAttempt())
                .startedAt(now()).build();
        migrations.insert(migration); row.setMigrationId(migration.getId()); jobs.update(row);
    }
    public void migrationState(DurableTaskJob row,String state) {
        if(row.getMigrationId()==null) return;
        MigrationTask migration=new MigrationTask(); migration.setId(row.getMigrationId()); migration.setStatus(state);
        migration.setRetryCount(row.getAttempt()); migration.setErrorMessage(row.getWaitingReason());
        if("COMPLETED".equals(state)||"FAILED".equals(state)||"CANCELLED".equals(state)) migration.setFinishedAt(now());
        migrations.updateLifecycle(migration);
        if("COMPLETED".equals(state)||"FAILED".equals(state)||"CANCELLED".equals(state)) orchestrator.getObject().clearDurableTransfer(row);
    }
    public void requestExternalPreparation(DurableTaskJob job) {
        jobs.update(job);
        external.getObject().prepareAsync(job, () -> orchestrator.getObject().prepareExternalJob(job));
    }

    @org.springframework.transaction.annotation.Transactional
    public void enqueueRegistered(Integer taskId,List<Long> ids,Long imageId,ResourceRequirements overrides,String mode) {
        for(Long id:ids) for(String m:TaskV1Service.MODE_COMPARISON.equals(mode)
                ? Arrays.asList(TaskV1Service.MODE_IN_PLACE,TaskV1Service.MODE_CENTRALIZED):Collections.singletonList(mode)) {
            DurableTaskJob row=newJob(taskId,id,m); row.setRuntimeImageId(imageId); row.setOverridesJson(write(overrides)); jobs.insert(row);
        }
        setTaskState(taskId,"等待资源");
    }
    @org.springframework.transaction.annotation.Transactional
    public void enqueueExternal(Long planId,Integer taskId,List<SchedulingAssignment> assignments) {
        TaskManagement task=tasks.getTaskByTaskId(taskId);
        if(task==null) throw new IllegalStateException("外部方案关联任务不存在");
        for(SchedulingAssignment a:assignments) {
            DurableTaskJob row=newJob(taskId,a.getDatasetId(),"EXTERNAL"); row.setPlanId(planId); row.setAssignmentId(a.getAssignmentId());
            row.setJobKey("external-"+a.getAssignmentId()); row.setReplicaId(a.getReplicaId());
            row.setRuntimeImageId(task.getRuntimeImageId()); row.setOverridesJson(task.getResourceOverridesJson());
            NodeManagement target=nodes.getNodeById(a.getTargetNodeId());
            if(target==null) throw new IllegalStateException("外部方案目标节点不存在");
            row.setTargetNode(target.getNodeName()); row.setClusterId(target.getCluster()); jobs.insert(row);
        }
        plans.updatePlanStatus(planId,"RUNNING",null); setTaskState(taskId,"等待资源");
    }
    private DurableTaskJob newJob(Integer taskId,Long id,String mode) {
        DurableTaskJob row=new DurableTaskJob(); row.setTaskId(taskId); row.setDatasetId(id); row.setExecutionMode(mode);
        row.setJobKey(taskId+"-"+id+"-"+mode); row.setState("WAITING"); row.setQueuedAt(now()); row.setAttempt(0); row.setExternalPrepared(false); return row;
    }
    /** Cancellation is asynchronous: terminal state is only published after every owned Pod has gone. */
    public void cancel(Integer taskId) {
        if(tasks.getTaskByTaskId(taskId)==null) throw RegistrationException.notFound("task not found: "+taskId);
        if(!owns(taskId)) throw RegistrationException.conflict("旧版任务不支持在线取消，请等待任务结束");
        if(!hasLiveJobs(taskId)) return;
        withLock(() -> {
            for(DurableTaskJob row:jobs.listByTask(taskId)) if(!row.terminal()) {
                row.setState("CANCELLING"); row.setWaitingReason("正在停止作业并释放资源"); jobs.update(row);
            }
            setTaskState(taskId,"取消中");
        },true);
    }
    public boolean hasLiveJobs(Integer taskId) { return jobs.listByTask(taskId).stream().anyMatch(j -> !j.terminal()); }

    @Scheduled(fixedDelayString="${app.resource-control.poll-interval-ms:3000}")
    public void tick() {
        try { withLock(this::reconcile,false); }
        catch(Exception ex) { log.error("持久化计算调度同步失败，下次重试",ex); }
    }
    private void withLock(Runnable action,boolean required) {
        // Advisory lock is connection-scoped; hold this exact connection through the entire decision pass.
        try(Connection connection=dataSource.getConnection(); PreparedStatement lock=connection.prepareStatement("SELECT GET_LOCK('topic4-compute-scheduler',"+(required?5:0)+")")) {
            try(ResultSet result=lock.executeQuery()) {
                if(!result.next() || result.getInt(1)!=1) {
                    if(required) throw RegistrationException.conflict("调度状态正在更新，请稍后重试");
                    return;
                }
            }
            try { action.run(); }
            finally { try(PreparedStatement release=connection.prepareStatement("SELECT RELEASE_LOCK('topic4-compute-scheduler')")) { release.execute(); } }
        } catch(SQLException ex) { throw new IllegalStateException("无法获得计算调度数据库锁",ex); }
    }
    private void reconcile() {
        List<DurableTaskJob> active=jobs.listActive();
        // Observe running work first. Queued work never consumes an executor thread.
        for(DurableTaskJob row:active) if(!"WAITING".equals(row.getState())
                && (row.getNextAttemptAt()==null || !row.getNextAttemptAt().isAfter(now()) || "CANCELLING".equals(row.getState()))) observe(row);
        active=jobs.listActive();
        if(active.isEmpty()) { finishTasks(); cleanupTerminalJobs(); return; }
        List<NodeManagement> compute=placement.schedulableComputeNodes();
        NodeResourceLedger ledger=resources.snapshot(compute);
        if(ledger==null) { for(DurableTaskJob row:active) if("WAITING".equals(row.getState())) waiting(row,"节点资源信息暂不可用"); finishTasks(); return; }
        // Requests of bound Pods are already in the Kubernetes snapshot. Reserve only unbound intents here.
        for(DurableTaskJob row:active) if(!"WAITING".equals(row.getState()) && row.getTargetNode()!=null) {
            NodeManagement node=nodes.getNodeByName(row.getTargetNode());
            if(node!=null && !ledger.accountsForJob(row.getJobName())) ledger.reserve(node,demand(row));
        }
        Map<Integer,Long> counts=active.stream().filter(j -> !"WAITING".equals(j.getState()))
                .collect(Collectors.groupingBy(DurableTaskJob::getTaskId,Collectors.counting()));
        for(DurableTaskJob row:active) if("WAITING".equals(row.getState())) {
            if(row.getNextAttemptAt()!=null && row.getNextAttemptAt().isAfter(now())) continue;
            if(waitTimeout>0 && row.getQueuedAt().plusMinutes(waitTimeout).isBefore(now())) { fail(row,"等待资源超时"); continue; }
            if(counts.getOrDefault(row.getTaskId(),0L)>=Math.max(1,maxPerTask)) { waiting(row,"等待本任务的其他作业释放并发名额"); continue; }
            try {
                RegisteredDataset dataset=datasets.findDatasetById(row.getDatasetId());
                if(dataset==null || !"ACTIVE".equals(dataset.getStatus())) { fail(row,"数据集不可用"); continue; }
                if("EXTERNAL".equals(row.getExecutionMode())) {
                    // Older queued external jobs did not snapshot their parent task's explicit settings.
                    TaskManagement parent=tasks.getTaskByTaskId(row.getTaskId());
                    if(parent!=null) {
                        if(row.getRuntimeImageId()==null) row.setRuntimeImageId(parent.getRuntimeImageId());
                        if(row.getOverridesJson()==null || row.getOverridesJson().trim().isEmpty() || "null".equals(row.getOverridesJson().trim()))
                            row.setOverridesJson(parent.getResourceOverridesJson());
                    }
                }
                ResourceRequirements overrides=readOverrides(row);
                long largestInput=datasets.listReplicas(row.getDatasetId()).stream().map(DatasetReplica::getSizeBytes)
                        .filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0L);
                double storageGi=Math.max(JobResourceDemand.DEFAULT_EPHEMERAL_STORAGE_GI, largestInput/1073741824.0*2+1);
                Long imageId=row.getRuntimeImageId()!=null?row.getRuntimeImageId():dataset.getDefaultRuntimeImageId();
                RuntimeImage image=imageId==null?null:images.findById(imageId);
                if(image==null || !"READY".equals(image.getStatus()) || !Boolean.TRUE.equals(image.getEnabled())
                        || image.getResolvedDigest()==null || image.getResolvedDigest().trim().isEmpty()) { fail(row,"运行镜像不可用"); continue; }
                Double requestedGpu=overrides!=null && overrides.getGpu()!=null?overrides.getGpu():dataset.getRequiredGpu();
                if(requestedGpu==null) requestedGpu=image.getDefaultGpu();
                Double requestedCpu=overrides!=null && overrides.getCpu()!=null?overrides.getCpu():dataset.getRequiredCpu();
                if(requestedCpu==null) requestedCpu=image.getDefaultCpu();
                Double requestedMemory=overrides!=null && overrides.getMemoryGi()!=null?overrides.getMemoryGi():dataset.getRequiredMemoryGi();
                if(requestedMemory==null) requestedMemory=image.getDefaultMemoryGi();
                JobResourceDemand wanted=JobResourceDemand.of(requestedCpu,requestedMemory,
                        requestedGpu,storageGi);
                row.setRuntimeImageId(imageId);
                row.setCpu(wanted.getCpu()); row.setMemoryGi(wanted.getMemoryGi()); row.setGpu(wanted.getGpu()); row.setEphemeralStorageGi(wanted.getEphemeralStorageGi());
                List<InPlacePlacementService.Placement> options=TaskV1Service.MODE_IN_PLACE.equals(row.getExecutionMode())
                        ?placement.candidates(datasets.listReplicas(row.getDatasetId()),compute)
                        :fixedCandidates(row,compute);
                if(options.isEmpty()) { waiting(row,"暂无满足数据访问和节点约束的可用计算节点"); continue; }
                boolean known=options.stream().allMatch(p -> ledger.availableCpu(NodeResourceLedger.key(p.getComputeNode()))!=null);
                boolean ever=options.stream().anyMatch(p -> ledger.canEverFit(p.getComputeNode(),wanted));
                if(known && !ever) { fail(row,"资源规格超过所有候选节点的可分配容量"); continue; }
                InPlacePlacementService.Placement selected=options.stream()
                        .filter(p -> ledger.fits(p.getComputeNode(),wanted)).findFirst().orElse(null);
                if(selected==null) { waiting(row,"候选节点CPU、内存、GPU或临时存储不足，等待资源释放"); continue; }
                TaskManagement task=tasks.getTaskByTaskId(row.getTaskId());
                if(task!=null && TaskV1Service.MODE_COMPARISON.equals(task.getExecutionMode())
                        && TaskV1Service.MODE_CENTRALIZED.equals(row.getExecutionMode())) {
                    DurableTaskJob reference=jobs.listByTask(row.getTaskId()).stream()
                            .filter(j -> j.getDatasetId().equals(row.getDatasetId()) && TaskV1Service.MODE_IN_PLACE.equals(j.getExecutionMode()))
                            .findFirst().orElse(null);
                    if(reference==null || reference.getTargetNode()==null || "WAITING".equals(reference.getState())) {
                        if(reference!=null && reference.terminal()) fail(row,"原位作业未获得有效放置，无法确定对比传输基准");
                        else waiting(row,"等待原位作业确定对比传输基准节点");
                        continue;
                    }
                    row.setComparisonNode(reference.getTargetNode());
                }
                if(!"EXTERNAL".equals(row.getExecutionMode())) row.setReplicaId(selected.getReplica().getReplicaId());
                row.setTargetNode(selected.getComputeNode().getNodeName()); row.setClusterId(selected.getComputeNode().getCluster());
                row.setState("SUBMITTING"); row.setWaitingReason(null);
                if(TaskV1Service.MODE_IN_PLACE.equals(row.getExecutionMode())
                        && row.getTargetNode().equals(orchestrator.getObject().resolveCentralNodeName())) row.setComparisonNode(row.getTargetNode());
                row.setJobName("t4-"+row.getTaskId()+"-"+row.getId()+"-"+row.getAttempt());
                row.setSubmittedAt(null); jobs.update(row); // identity commits BEFORE an API request can reach Kubernetes
                if("EXTERNAL".equals(row.getExecutionMode()) && row.getAssignmentId()!=null)
                    plans.updateAssignmentStatus(row.getAssignmentId(),"RUNNING",null);
                recordSelection(row,selected,options,ledger);
                ledger.reserve(selected.getComputeNode(),wanted);
                counts.put(row.getTaskId(),counts.getOrDefault(row.getTaskId(),0L)+1);
                observe(row);
            } catch(IllegalArgumentException ex) { fail(row,safeMessage(ex)); }
            catch(Exception ex) { transientFailure(row,ex); }
        }
        refreshTaskStates(); finishTasks(); cleanupTerminalJobs();
    }
    /** Explicit and centralized modes never required a measured logical route before resource admission. */
    private List<InPlacePlacementService.Placement> fixedCandidates(DurableTaskJob row,List<NodeManagement> compute) {
        boolean externalMode="EXTERNAL".equals(row.getExecutionMode());
        String targetName=externalMode?row.getTargetNode():orchestrator.getObject().resolveCentralNodeName();
        NodeManagement target=compute.stream().filter(n -> targetName.equals(n.getNodeName())).findFirst().orElse(null);
        if(target==null) return Collections.emptyList();
        List<DatasetReplica> replicas;
        if(externalMode) {
            DatasetReplica replica=datasets.findReplicaById(row.getReplicaId());
            replicas=replica==null?Collections.emptyList():Collections.singletonList(replica);
        } else replicas=new ArrayList<>(datasets.listReplicas(row.getDatasetId()));
        List<InPlacePlacementService.Placement> result=new ArrayList<>();
        for(DatasetReplica replica:replicas) {
            if(!row.getDatasetId().equals(replica.getDatasetId())) continue;
            // A completed MOVE deliberately leaves the declared source MISSING. The execution path
            // independently verifies its prepared target against dataset-version authority before use.
            boolean prepared=externalMode && Boolean.TRUE.equals(row.getExternalPrepared());
            if(!prepared && !replicaAvailability.evaluate(replica).isUsable()) continue;
            NodeManagement source=nodes.getNodeById(replica.getNodeId());
            if(source==null) continue;
            boolean local=source.getNodeId().equals(target.getNodeId());
            result.add(new InPlacePlacementService.Placement(replica,source,target,
                    local?InPlacePlacementService.Tier.LOCAL:InPlacePlacementService.Tier.NEAREST,
                    null,Collections.emptyList(),null));
        }
        if(!externalMode) result.sort(Comparator.comparing(p -> !p.getReplicaNode().getNodeId().equals(target.getNodeId())));
        return result;
    }

    private void recordSelection(DurableTaskJob row,InPlacePlacementService.Placement selected,
            List<InPlacePlacementService.Placement> options,NodeResourceLedger ledger) {
        Map<String,Object> details=new LinkedHashMap<>();
        details.put("sourceNode",selected.getReplicaNode().getNodeName()); details.put("replicaId",row.getReplicaId());
        details.put("targetNode",row.getTargetNode()); details.put("tier",selected.getTier());
        details.put("candidateCount",options.size()); details.put("cpuRequested",row.getCpu());
        details.put("memoryRequestedGi",row.getMemoryGi()); details.put("gpuRequested",row.getGpu());
        details.put("ephemeralStorageRequestedGi",row.getEphemeralStorageGi());
        details.put("cpuFree",ledger.availableCpu(NodeResourceLedger.key(selected.getComputeNode())));
        details.put("memoryFreeGi",ledger.availableMemoryGi(NodeResourceLedger.key(selected.getComputeNode())));
        details.put("latencyMs",selected.getPath()==null?null:selected.getPath().getLatencyMs());
        details.put("bandwidthMbps",selected.getPath()==null?null:selected.getPath().getBandwidthMbps());
        details.put("reason","EXTERNAL".equals(row.getExecutionMode())
                ?"使用外部方案明确指定的源副本与目标节点；检查节点资源，不要求逻辑拓扑测量路径"
                :TaskV1Service.MODE_CENTRALIZED.equals(row.getExecutionMode())
                    ?"使用固定中心节点；优先中心节点已有副本，否则按可用副本原顺序选择；检查节点资源，不要求逻辑拓扑测量路径"
                    :"沿用本地、同站点、跨站点、中心节点回退顺序；跨站点按延迟升序、瓶颈带宽降序；跳过资源不足候选");
        TaskExecutionEvent event=TaskExecutionEvent.builder().taskId(row.getTaskId()).datasetId(row.getDatasetId())
                .executionMode(row.getExecutionMode()).eventType("PLACEMENT_SELECTED").nodeName(row.getTargetNode())
                .occurredAt(now()).detailsJson(write(details)).build(); tasks.insertExecutionEvent(event);
    }

    private JobResourceDemand demand(DurableTaskJob row) { return JobResourceDemand.of(row.getCpu(),row.getMemoryGi(),row.getGpu(),row.getEphemeralStorageGi()); }
    private ResourceRequirements readOverrides(DurableTaskJob row) {
        try { return row.getOverridesJson()==null?null:json.readValue(row.getOverridesJson(),ResourceRequirements.class); }
        catch(Exception ex) { throw new IllegalStateException("无法读取持久化资源规格",ex); }
    }
    private void observe(DurableTaskJob row) {
        try {
            if(tasks.getTaskByTaskId(row.getTaskId())==null) { row.setState("CANCELLING"); jobs.update(row); }
            if ("EXTERNAL".equals(row.getExecutionMode()) && external.getObject().isPreparing(row.getId())) return;
            if("CANCELLING".equals(row.getState()) || "FAILING".equals(row.getState())) {
                if(deleteAndGone(row)) {
                    if("CANCELLING".equals(row.getState())) { row.setState("CANCELLED"); row.setWaitingReason("任务已取消，资源已释放"); row.setFinishedAt(now()); jobs.update(row); migrationState(row,"CANCELLED"); }
                    else if("FAILING".equals(row.getState())) { row.setState("FAILED"); row.setFinishedAt(now()); jobs.update(row); migrationState(row,"FAILED"); }
                }
                return;
            }
            if(row.getBoundAt()==null && waitTimeout>0 && row.getQueuedAt().plusMinutes(waitTimeout).isBefore(now())) {
                row.setState("FAILING"); row.setWaitingReason("等待节点资源超时，正在停止作业"); jobs.update(row); return;
            }
            DataItemResult result="EXTERNAL".equals(row.getExecutionMode())
                    ?orchestrator.getObject().advanceExternalJob(row):orchestrator.getObject().advanceRegisteredJob(row,readOverrides(row));
            if(result!=null) {
                row.setResultJson(write(result)); row.setState("COMPLETED"); row.setFinishedAt(now()); row.setWaitingReason(null); jobs.update(row); migrationState(row,"COMPLETED");
                // Keep completed Jobs until aggregation. Their TTL is removed while controlled to survive restarts.
            }
        } catch(JobPending pending) { jobs.update(row); }
        catch(JobFailed | IllegalArgumentException ex) {
            row.setState(row.getJobName()==null?"FAILED":"FAILING"); row.setWaitingReason(safeMessage(ex));
            if("FAILED".equals(row.getState())) row.setFinishedAt(now()); jobs.update(row);
        }
        catch(io.fabric8.kubernetes.client.KubernetesClientException ex) { transientFailure(row,ex); }
        catch(IllegalStateException ex) {
            row.setState(row.getJobName()==null?"FAILED":"FAILING"); row.setWaitingReason(safeMessage(ex)); jobs.update(row);
        }
        catch(Exception ex) { transientFailure(row,ex); }
    }
    private void transientFailure(DurableTaskJob row,Exception ex) {
        // Transport failures do not prove that a Job failed or disappeared. Keep its stable identity and retry observation.
        row.setWaitingReason(safeMessage(ex)); row.setNextAttemptAt(now().plusSeconds(5)); jobs.update(row);
        log.warn("计算作业状态待确认 id={} job={} reason={}",row.getId(),row.getJobName(),row.getWaitingReason());
    }
    private String safeMessage(Exception ex) { String text=ex.getMessage(); return text==null?ex.getClass().getSimpleName():text.replaceAll("(?i)(Bearer |token[=: ]+)[^\\s]+","$1[REDACTED]"); }
    private void waiting(DurableTaskJob row,String reason) { row.setWaitingReason(reason); jobs.update(row); }
    public void fail(DurableTaskJob row,String reason) {
        row.setWaitingReason(reason); row.setState("FAILED"); row.setFinishedAt(now()); jobs.update(row);
    }
    KubernetesClient client(DurableTaskJob row) {
        Map<String,KubernetesClient> clients=factory.getClusterClients();
        KubernetesClient client=clients.get(row.getClusterId());
        if(client==null && clients.size()==1) client=clients.values().iterator().next();
        if(client==null) throw new IllegalStateException("目标集群客户端不可用"); return client;
    }
    boolean deleteAndGone(DurableTaskJob row) {
        if(row.getJobName()==null) return true;
        KubernetesClient client=client(row);
        Job job=client.batch().v1().jobs().inNamespace("default").withName(row.getJobName()).get();
        if(job!=null) {
            if(job.getMetadata().getLabels()==null || !row.getId().toString().equals(job.getMetadata().getLabels().get("topic4.io/compute-job-id"))
                    || !row.getTaskId().toString().equals(job.getMetadata().getLabels().get(InterruptedTaskRecovery.TASK_LABEL)))
                throw new IllegalStateException("Job身份不匹配，拒绝删除未知作业");
            if(row.getJobUid()!=null && !row.getJobUid().equals(job.getMetadata().getUid())) throw new IllegalStateException("Job UID发生变化，拒绝删除未知作业");
            client.batch().v1().jobs().inNamespace("default").withName(row.getJobName()).withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.FOREGROUND).delete();
        }
        boolean gone=client.batch().v1().jobs().inNamespace("default").withName(row.getJobName()).get()==null
                && client.pods().inNamespace("default").withLabel("job-name",row.getJobName()).list().getItems().isEmpty();
        if(gone) orchestrator.getObject().cleanupDurableCredential(row);
        return gone;
    }
    private void refreshTaskStates() {
        Map<Integer,List<DurableTaskJob>> byTask=jobs.listActive().stream().collect(Collectors.groupingBy(DurableTaskJob::getTaskId));
        byTask.forEach((id,rows) -> setTaskState(id,rows.stream().anyMatch(j -> "CANCELLING".equals(j.getState()))?"取消中":
                rows.stream().anyMatch(j -> "RUNNING".equals(j.getState()))?"计算中":
                rows.stream().anyMatch(j -> "PREPARING".equals(j.getState()))?"数据准备中":"等待资源"));
    }
    private void setTaskState(Integer id,String state) { TaskManagement t=new TaskManagement(); t.setTaskId(id); t.setStatus(state); tasks.updateExecutionSummary(t); }
    private void finishTasks() {
        for(Integer taskId:jobs.tasksToFinalize()) {
            List<DurableTaskJob> rows=jobs.listByTask(taskId);
            if(rows.stream().anyMatch(j -> "EXTERNAL".equals(j.getExecutionMode()))) external.getObject().finalizePlan(taskId,rows);
            else orchestrator.getObject().finalizeDurableTask(taskId,rows);
        }
    }
    private void cleanupTerminalJobs() {
        for(DurableTaskJob row:jobs.listCleanup()) try {
            if(deleteAndGone(row)) { row.setCleanedAt(now()); jobs.update(row); }
        } catch(Exception ex) { log.warn("完成作业清理待重试 id={}",row.getId(),ex); }
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    static final class JobFailed extends RuntimeException { JobFailed(String message) { super(message); } }
    static final class JobPending extends RuntimeException { JobPending() { super(null,null,false,false); } }
}
