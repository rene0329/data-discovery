package org.example.controller.admin;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.*;
import org.example.mapper.DataManagementMapper;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.service.K8sTaskOrchestratorService; // 引入新的后台服务
import org.example.vo.NodeManagementVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.example.vo.ApiResponse;
import org.example.vo.PageResult;

@RestController
@RequestMapping("/common")
@Slf4j
public class CommonController {

    private final DataManagementMapper dataManagementMapper;
    private final NodeManagementMapper nodeManagementMapper;
    private final TaskManagementMapper taskManagementMapper;
    private final EdgeManagementMapper edgeManagementMapper;
    private final K8sTaskOrchestratorService k8sTaskOrchestratorService;
    private final RestTemplate restTemplate;

    // data-discovery DaemonSet 寻址配置
    @Value("${dispatch.data-discovery.port:8080}")
    private int discoveryPort;

    @Autowired
    public CommonController(
            DataManagementMapper dataManagementMapper,
            NodeManagementMapper nodeManagementMapper,
            TaskManagementMapper taskManagementMapper,
            EdgeManagementMapper edgeManagementMapper,
            K8sTaskOrchestratorService k8sTaskOrchestratorService
    ) {
        this.dataManagementMapper = dataManagementMapper;
        this.nodeManagementMapper = nodeManagementMapper;
        this.taskManagementMapper = taskManagementMapper;
        this.edgeManagementMapper = edgeManagementMapper;
        this.k8sTaskOrchestratorService = k8sTaskOrchestratorService;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 构建 data-discovery DaemonSet URL。
     * 使用 hostNetwork 模式：Pod IP = 节点 internal_ip，Pod 重启后 IP 不变。
     */
    private String discoveryBaseUrl(String nodeName) {
        String ip = nodeManagementMapper.getNodeIpByDataServer(nodeName);
        if (ip == null || ip.isEmpty()) {
            throw new IllegalStateException("找不到节点 " + nodeName + " 的 IP，请检查 node_management 表");
        }
        return String.format("http://%s:%d", ip, discoveryPort);
    }

    /**
     * 物理迁移文件：从 sourceNode 下载，上传到 targetNode。
     * 文件名为 dataName（相对路径，直接使用 data_management.data_name）。
     *
     * @param deleteSource 若为 true，上传成功后删除源节点文件（主迁移/move）；
     *                     若为 false，仅复制不删除（备份/copy）。
     * @return true=迁移成功；false=失败（调用方应保留旧 data_server）
     */
    private boolean migrateFile(String dataName, String sourceNode, String targetNode, boolean deleteSource) {
        String downloadUrl = discoveryBaseUrl(sourceNode) + "/data-discovery/download/" + dataName;
        String uploadUrl   = discoveryBaseUrl(targetNode) + "/data-discovery/upload";
        try {
            // 1. 下载文件到内存（适合中小文件；大文件可考虑流式改造）
            byte[] fileBytes = restTemplate.getForObject(downloadUrl, byte[].class);
            if (fileBytes == null || fileBytes.length == 0) {
                log.warn("物理迁移: 从 {} 下载 {} 得到空响应，跳过迁移", sourceNode, dataName);
                return false;
            }
            // 2. 上传到目标节点
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new ByteArrayResource(fileBytes) {
                @Override public String getFilename() { return dataName; }
            });
            body.add("path", dataName);
            restTemplate.postForObject(uploadUrl, new HttpEntity<>(body, headers), Map.class);
            log.info("物理迁移成功: {} {} -> {} ({} bytes)", dataName, sourceNode, targetNode, fileBytes.length);
            // 3. 删除源节点文件（move 语义）
            if (deleteSource) {
                String deleteUrl = discoveryBaseUrl(sourceNode) + "/data-discovery/delete/" + dataName;
                try {
                    restTemplate.delete(deleteUrl);
                    log.info("已删除源节点 {} 上的文件: {}", sourceNode, dataName);
                } catch (Exception ex) {
                    log.warn("删除源文件失败（非致命）: {} @ {}: {}", dataName, sourceNode, ex.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            log.error("物理迁移失败: {} {} -> {}: {}", dataName, sourceNode, targetNode, e.getMessage());
            return false;
        }
    }


    /**
     * 用户提交数据 (已重构为异步委派模式)
     * 此方法现在非常快速，它会立即返回响应，并将长时间运行的任务交给后台服务处理。
     */
    @PostMapping("/submitData/{currentTaskId}")
    public ResponseEntity<ApiResponse<Integer>> submitData(@PathVariable Integer currentTaskId, @RequestBody List<String> selectedDatas) {
        log.info("接收到调度任务请求，任务ID: {}", currentTaskId);

        if (selectedDatas == null || selectedDatas.isEmpty()) {
            log.warn("提交的数据列表为空，任务 {} 中止。", currentTaskId);
            return ResponseEntity.ok(ApiResponse.ok(0)); // 0 表示没有处理任何数据
        }

        // 1. (快速) 创建总任务记录，初始状态为 "执行中"
        TaskManagement taskManagement = TaskManagement.builder()
                .taskName("任务" + currentTaskId)
                .selectedData(selectedDatas.toString())
                .status("执行中")
                .createTime(LocalDateTime.now())
                .build();
        taskManagementMapper.submitData(taskManagement);
        Integer taskId = taskManagement.getTaskId(); // 获取数据库生成的自增ID

        // 2. (快速) 更新相关数据热度
        for (String data : selectedDatas) {
            dataManagementMapper.incrementDataCount(data);
        }

        // 3. (核心) 将包含所有复杂逻辑的任务异步委派给后台服务
        //    这个调用会立即返回，不会阻塞当前请求线程。
        k8sTaskOrchestratorService.executeTask(taskId, selectedDatas);
        log.info("任务 {} 已成功提交至后台异步执行。立即返回HTTP响应。", taskId);

        // 4. 立即返回成功响应，告知客户端任务已接收
        return ResponseEntity.ok(ApiResponse.ok(1)); // 1 表示任务已成功接收
    }


    /**
     * 仪表盘的接口 - 返回原始数值，前端自己计算百分比
     * @param nodeManagement
     * @return
     */
    @PutMapping("/yiBiaoPan")
    public ResponseEntity<ApiResponse<NodeManagementVO>> getNodeData(@RequestBody NodeManagement nodeManagement) {
        NodeManagement data = nodeManagementMapper.getNodeDataByNodeName(nodeManagement);

        log.info("仪表盘数据 - 节点: {}, CPU: {}/{}, 内存: {}/{}",
                data.getNodeName(),
                data.getCurrentCpu(), data.getMaxCpu(),
                data.getCurrentMemory(), data.getMaxMemory());

        // ✅ 返回原始数值，不计算百分比
        NodeManagementVO nodeManagementVO = NodeManagementVO.builder()
                .nodeName(data.getNodeName())
                .currentCpu(data.getCurrentCpu())
                .maxCpu(data.getMaxCpu())
                .currentMemory(data.getCurrentMemory())
                .maxMemory(data.getMaxMemory())
                // 不再返回 storage
                .build();

        return ResponseEntity.ok(ApiResponse.ok(nodeManagementVO));
    }

    /**
     * 获取数据--》前端效果就是更新数据
     * @param dataManagement
     * @return
     */
    @PostMapping("/updateOneHeat")
    public ResponseEntity<ApiResponse<DataManagement>> updateOneHeat(@RequestBody DataManagement dataManagement) {
//        log.info("开始updateOneHeat...");

        DataManagement data = dataManagementMapper.updateOneHeat(dataManagement);
//        System.out.println(data);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * 管理员的数据管理的存储操作
     * @param dataManagement
     * @return
     */
    @PutMapping("/save")
    public ResponseEntity<ApiResponse<DataManagement>> save(@RequestBody DataManagement dataManagement) {

        log.info("开始重新分配数据存储位置...");

        // 获取中心性的 storage 节点列表
        List<NodeManagement> centralityNodes = dataManagementMapper.getCentralityNodes();

        // 获取所有待存储的数据，按热度从高到低排序
        List<DataManagement> hotDataList = dataManagementMapper.getAllDataByHeat();

        // 清空所有数据的存储位置
        nodeManagementMapper.clearAllDataServers();

        int remainingDataCount = hotDataList.size();

        for (NodeManagement node : centralityNodes) {
            // 动态计算当前节点的存储数据数量（清空后此值为0）
            int currentStorageCount = nodeManagementMapper.getDataCountByNode(node.getNodeName());

            int availableCapacity = node.getNumDataset() - currentStorageCount;

            // 如果当前节点的可用容量足够存储剩余数据
            if (availableCapacity >= remainingDataCount) {
                for (DataManagement data : hotDataList) {
                    data.setDataServer(node.getNodeName());
                    dataManagementMapper.updateDataServer(data);
                }
                break;
            } else {
                for (int i = 0; i < availableCapacity; i++) {
                    DataManagement data = hotDataList.remove(0);
                    data.setDataServer(node.getNodeName());
                    dataManagementMapper.updateDataServer(data);
                }
                remainingDataCount -= availableCapacity;
            }
        }

        // 不论剩余数据数量如何，始终将热度最高的两个数据备份到中心性最低的两个节点
        for (int i = 0; i < 2 && !hotDataList.isEmpty(); i++) {
            DataManagement data = hotDataList.remove(0);
            NodeManagement lastNode = centralityNodes.get(centralityNodes.size() - 1 - i);
            data.setBackupServer(lastNode.getNodeName());
            dataManagementMapper.updateBackupServer(data);
        }


        // Re-fetch the saved data and return it
        DataManagement savedData = dataManagementMapper.getData(dataManagement);
        return ResponseEntity.ok(ApiResponse.ok(savedData));

    }


    /**
     * 管理员的数据管理的存储操作

     */
    /**
     * 多因子热敏制导存储分配（热敏存储 & 原位汇聚共用）。
     * <p>
     * 评分公式:
     *   score = W_CAP  * freeCapRatio     -- 剩余容量比，防止塞满
     *         - W_HEAT * heatLoadRatio    -- 已分配热度占比，防止热数据扎堆
     *         + W_PROX * computeProxScore -- 与计算节点直接相邻度，提升就近访问效率
     * <p>
     * 物理迁移：对每条数据，若新分配节点 ≠ 旧节点，则先通过
     *   GET  {oldNode}/data-discovery/download/{dataName}  下载文件
     *   POST {newNode}/data-discovery/upload               上传到目标节点
     * 物理迁移成功后才更新 DB；失败则保留旧 data_server，本轮跳过。
     */
    @Transactional
    @GetMapping("/saveAll")
    public ResponseEntity<ApiResponse<List<DataManagement>>> saveAll() {
        log.info("开始多因子热敏制导存储分配（含物理迁移）...");

        // ── 1. 可存储节点（存储节点 + 计算存储双角色节点）
        List<NodeManagement> storageNodes = dataManagementMapper.getCentralityNodes();
        if (storageNodes.isEmpty()) {
            log.warn("未找到存储节点，分配终止");
            return ResponseEntity.ok(ApiResponse.ok(dataManagementMapper.getAllData()));
        }

        // ── 2. 数据列表（热度降序），同时记录旧 data_server
        List<DataManagement> dataList = dataManagementMapper.getAllDataByHeat();
        if (dataList.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.ok(dataManagementMapper.getAllData()));
        }
        Map<String, String> oldServerMap = new HashMap<>();
        Map<String, String> oldBackupMap = new HashMap<>();
        for (DataManagement d : dataList) {
            oldServerMap.put(d.getDataName(), d.getDataServer());
            oldBackupMap.put(d.getDataName(), d.getBackupServer());
        }

        // ── 3. 预计算 computeProxScore：与直连计算节点数 / 全部计算节点数，值域 [0,1]
        List<NodeManagement> allNodes = nodeManagementMapper.selectAllNodes();
        Map<Integer, String> nodeTypeMap = new HashMap<>();
        for (NodeManagement n : allNodes) {
            nodeTypeMap.put(n.getNodeId(), n.getType());
        }
        long totalComputeNodes = 0;
        for (String t : nodeTypeMap.values()) {
            if ("compute".equals(t) || "compute-storage".equals(t)) totalComputeNodes++;
        }
        Map<Integer, Set<Integer>> adjacency = new HashMap<>();
        for (EdgeManagement e : edgeManagementMapper.selectAllEdges()) {
            adjacency.computeIfAbsent(e.getSourceId(), k -> new HashSet<>()).add(e.getTargetId());
            adjacency.computeIfAbsent(e.getTargetId(), k -> new HashSet<>()).add(e.getSourceId());
        }
        Map<Integer, Double> proxScoreMap = new HashMap<>();
        for (NodeManagement sn : storageNodes) {
            if (totalComputeNodes == 0) { proxScoreMap.put(sn.getNodeId(), 0.0); continue; }
            Set<Integer> neighbors = adjacency.getOrDefault(sn.getNodeId(), Collections.emptySet());
            long cn = 0;
            for (Integer nb : neighbors) {
                String t = nodeTypeMap.get(nb);
                if ("compute".equals(t) || "compute-storage".equals(t)) cn++;
            }
            proxScoreMap.put(sn.getNodeId(), (double) cn / totalComputeNodes);
        }

        // ── 4. 动态状态初始化
        Map<Integer, Integer> assignedCount = new HashMap<>();
        Map<Integer, Double>  heatAccum     = new HashMap<>();
        for (NodeManagement sn : storageNodes) {
            assignedCount.put(sn.getNodeId(), 0);
            heatAccum.put(sn.getNodeId(), 0.0);
        }
        double totalHeat = 0.0;
        for (DataManagement d : dataList) {
            if (d.getDataHeat() != null) totalHeat += d.getDataHeat();
        }

        final double W_CAP  = 0.4;
        final double W_HEAT = 0.4;
        final double W_PROX = 0.2;
        int migratedCount = 0, skippedCount = 0;

        // ── 5. 逐条打分 → 物理迁移（若有必要）→ 更新 DB
        for (DataManagement data : dataList) {
            double heat = data.getDataHeat() != null ? data.getDataHeat() : 0.0;
            NodeManagement best      = null;
            double         bestScore = Double.NEGATIVE_INFINITY;

            for (NodeManagement sn : storageNodes) {
                int cap  = sn.getNumDataset() != null ? sn.getNumDataset() : 0;
                int used = assignedCount.get(sn.getNodeId());
                if (used >= cap) continue;

                double freeCapRatio  = cap > 0 ? (double)(cap - used) / cap : 0.0;
                double heatLoadRatio = totalHeat > 0 ? heatAccum.get(sn.getNodeId()) / totalHeat : 0.0;
                double prox          = proxScoreMap.getOrDefault(sn.getNodeId(), 0.0);
                double score         = W_CAP * freeCapRatio - W_HEAT * heatLoadRatio + W_PROX * prox;

                if (score > bestScore) { bestScore = score; best = sn; }
            }

            if (best == null) {
                log.warn("所有存储节点容量已满，数据项 '{}' 无法分配", data.getDataName());
                continue;
            }

            String oldServer = oldServerMap.get(data.getDataName());
            String newServer = best.getNodeName();
            boolean needsMove = oldServer != null && !oldServer.isEmpty() && !oldServer.equals(newServer);

            if (needsMove) {
                boolean ok = migrateFile(data.getDataName(), oldServer, newServer, true);
                if (!ok) {
                    log.warn("物理迁移失败，'{}' 保留在原节点 {}，跳过 DB 更新", data.getDataName(), oldServer);
                    skippedCount++;
                    // 评分状态仍按新布局计入（保持评分一致性）
                    assignedCount.merge(best.getNodeId(), 1, Integer::sum);
                    heatAccum.merge(best.getNodeId(), heat, Double::sum);
                    continue;
                }
                migratedCount++;
            }

            // 文件已就位（原地 or 迁移成功），更新 DB
            data.setDataServer(newServer);
            data.setDataNodeId(best.getNodeId());
            dataManagementMapper.updateDataServer(data);
            assignedCount.merge(best.getNodeId(), 1, Integer::sum);
            heatAccum.merge(best.getNodeId(), heat, Double::sum);
            log.debug("分配 '{}' → {} (moved={})", data.getDataName(), newServer, needsMove);
        }

        // ── 6. 冗余备份：热度最高 min(2,N) 条数据，各选最优非主节点
        int backupCount = Math.min(2, dataList.size());
        for (int i = 0; i < backupCount; i++) {
            DataManagement data        = dataList.get(i);
            String         primaryNode = data.getDataServer();
            NodeManagement backupBest  = null;
            double         backupScore = Double.NEGATIVE_INFINITY;

            for (NodeManagement sn : storageNodes) {
                if (sn.getNodeName().equals(primaryNode)) continue;
                int cap  = sn.getNumDataset() != null ? sn.getNumDataset() : 0;
                int used = assignedCount.get(sn.getNodeId());
                if (used > cap) continue; // 允许轻微超配 1 个以保证冗余

                double freeCapRatio  = cap > 0 ? (double)(cap - used) / cap : 0.0;
                double heatLoadRatio = totalHeat > 0 ? heatAccum.get(sn.getNodeId()) / totalHeat : 0.0;
                double prox          = proxScoreMap.getOrDefault(sn.getNodeId(), 0.0);
                double score         = W_CAP * freeCapRatio - W_HEAT * heatLoadRatio + W_PROX * prox;

                if (score > backupScore) { backupScore = score; backupBest = sn; }
            }

            if (backupBest == null) {
                log.warn("数据项 '{}' 找不到可用备份节点，跳过", data.getDataName());
                continue;
            }

            String oldBackup = oldBackupMap.get(data.getDataName());
            String newBackup = backupBest.getNodeName();
            // 仅当备份目标改变 或 从未备份过时，才需要物理复制（从主节点复制一份）
            boolean backupNeedsMove = primaryNode != null && !primaryNode.isEmpty()
                    && !newBackup.equals(oldBackup);

            if (backupNeedsMove) {
                boolean ok = migrateFile(data.getDataName(), primaryNode, newBackup, false);
                if (!ok) {
                    log.warn("备份物理复制失败，'{}' 保留旧备份节点 {}", data.getDataName(), oldBackup);
                    continue;
                }
            }

            data.setBackupServer(newBackup);
            dataManagementMapper.updateBackupServer(data);
            log.info("备份 '{}' → {}", data.getDataName(), newBackup);
        }

        log.info("分配完成：共 {} 条数据，物理迁移 {} 个，失败保留 {} 个，存储节点 {} 个",
                dataList.size(), migratedCount, skippedCount, storageNodes.size());
        return ResponseEntity.ok(ApiResponse.ok(dataManagementMapper.getAllData()));
    }

    @GetMapping("/updateAll")
    public ResponseEntity<ApiResponse<List<DataManagement>>> updateAll() {
        // 重新获取所有数据，返回给前端
        List<DataManagement> allData = dataManagementMapper.getAllData();
//        System.out.println(allData);
        return ResponseEntity.ok(ApiResponse.ok(allData));

    }



    @DeleteMapping("/clearTasks")
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearTasks() {
        Map<String, Object> response = new HashMap<>();
        try {
            taskManagementMapper.deleteAllTasks();
            taskManagementMapper.resetAutoIncrement();
            response.put("success", true);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Error clearing tasks: " + e.getMessage());
        }
        return ResponseEntity.ok(ApiResponse.ok(response));
    }


    /**
     * 管理员的网络配置
     * @param page
     * @param pageSize
     * @param query
     * @return
     */
    @GetMapping("/networkConfiguration")
    public ResponseEntity<ApiResponse<PageResult<NodeManagement>>> networkConfiguration(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {

//        log.info("开始networkConfiguration...");

        List<NodeManagement> nodeManagements = nodeManagementMapper.networkConfiguration(query);

        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(nodeManagements, page, pageSize)));
    }


    /**
     * 用户的数据分页
     * @param page
     * @param pageSize
     * @param query
     * @return
     */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PageResult<DataManagement>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {

//        log.info("开始list...");

        List<DataManagement> dataManagements = dataManagementMapper.list(query);

        // 构建响应体对象
        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(dataManagements, page, pageSize)));
    }

    /**
     * 管理员的数据管理分页
     * @param page
     * @param pageSize
     * @param query
     * @return
     */
    @GetMapping("/dataManagement")
    public ResponseEntity<ApiResponse<PageResult<DataManagement>>> dataManagement(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {

//        log.info("开始dataManagement...");

        List<DataManagement> dataManagements = dataManagementMapper.adminList(query);

        // 构建响应体对象
        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(dataManagements, page, pageSize)));
    }

    /**
     * 管理员的数据管理分页
     */
    @PostMapping("/updateDataStatus")
    public ResponseEntity<ApiResponse<DataManagement>> updateDataStatus(@RequestBody  DataManagement dataManagement)
    {
        dataManagementMapper.updateDataStatus(dataManagement.getDataName());
        DataManagement data =dataManagementMapper.getData(dataManagement);
//        System.out.println(data);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * 更新数据项（占位实现：调用 save 进行更新/插入）。
     */
    @PostMapping("/updateDataItem")
    public ResponseEntity<ApiResponse<DataManagement>> updateDataItem(@RequestBody DataManagement data) {
        dataManagementMapper.save(data);
        DataManagement refreshed = dataManagementMapper.getData(data);
        return ResponseEntity.ok(ApiResponse.ok(refreshed));
    }

    /**
     * 用户的任务列表分页展示
     * @param page
     * @param pageSize
     * @param query
     * @return
     */
    @GetMapping("/taskList")
    public ResponseEntity<ApiResponse<PageResult<TaskManagement>>> taskList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {

//        log.info("开始taskList...");

        List<TaskManagement> taskManagements = taskManagementMapper.list(query);

        // 构建响应体对象
        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(taskManagements, page, pageSize)));
    }

    /**
     * 调度计划列表（占位：返回空列表分页结构）。
     */
    @GetMapping("/scheduleList")
    public ResponseEntity<ApiResponse<PageResult<Object>>> scheduleList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {
        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(new ArrayList<>(), page, pageSize)));
    }

    /**
     * 性能分析数据（占位：返回空列表）。
     */
    @GetMapping("/analysisData")
    public ResponseEntity<ApiResponse<PageResult<Object>>> analysisData(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {
        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(new ArrayList<>(), page, pageSize)));
    }

    /**
     * 更新任务（占位实现：直接调用 mapper.updateTask）。
     */
    @PostMapping("/updateTask")
    public ResponseEntity<ApiResponse<TaskManagement>> updateTask(@RequestBody TaskManagement task) {
        taskManagementMapper.updateTask(task);
        TaskManagement refreshed = task.getTaskId() != null ? taskManagementMapper.getTaskByTaskId(task.getTaskId()) : task;
        return ResponseEntity.ok(ApiResponse.ok(refreshed));
    }

    /**
     * 删除任务（按 taskId）。
     */
    @PostMapping("/deleteTask")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTask(@RequestBody TaskManagement task) {
        Map<String, Object> resp = new HashMap<>();
        if (task.getTaskId() == null) {
            resp.put("success", false);
            resp.put("message", "taskId is required");
            return ResponseEntity.badRequest().body(ApiResponse.ok(resp));
        }
        taskManagementMapper.deleteTaskById(task.getTaskId());
        resp.put("success", true);
        return ResponseEntity.ok(ApiResponse.ok(resp));
    }

    /**
     * 对应前端的网络结构展示，获取所有节点信息
     * @return
     */
    @GetMapping("/networkConstruction")
    public ResponseEntity<ApiResponse<List<NodeManagement>>> networkConstruction() {

//        log.info("开始networkConstruction...");
        List<NodeManagement> nodeManagements = nodeManagementMapper.networkConstruction();
//        System.out.println(nodeManagements);

        return ResponseEntity.ok(ApiResponse.ok(nodeManagements));
    }

    /**
     * 对应前端的网络结构展示，获取所有边信息
     * @return
     */
    @GetMapping("/links")
    public ResponseEntity<ApiResponse<List<EdgeManagement>>> links() {

//        log.info("开始networkConstruction...");
        List<EdgeManagement> edgeManagements = edgeManagementMapper.links();

        return ResponseEntity.ok(ApiResponse.ok(edgeManagements));
    }

    // ====================== 前端常用数据/节点展示补充接口 ======================

    /**
     * 可选数据集列表（与 list 类似，用于前端 datasetList）。
     */
    @GetMapping("/datasetList")
    public ResponseEntity<ApiResponse<PageResult<DataManagement>>> datasetList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {

        List<DataManagement> dataManagements = dataManagementMapper.list(query);

        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(dataManagements, page, pageSize)));
    }

    /**
     * 提交选中数据集（占位实现：仅回显提交的 datasetIds）。
     */
    @PostMapping("/submitDatasets")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitDatasets(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("datasetIds", body.get("datasetIds"));
        return ResponseEntity.ok(ApiResponse.ok(resp));
    }

    /**
     * 节点配置列表（用于前端 nodeSettings）。
     */
    @GetMapping("/nodeSettings")
    public ResponseEntity<ApiResponse<PageResult<NodeManagement>>> nodeSettings(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {

        List<NodeManagement> nodes = nodeManagementMapper.networkConfiguration(query);

        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(nodes, page, pageSize)));
    }

    /**
     * 节点配置更新（示例：更新节点运行时资源或标签字段）。
     */
    @PostMapping("/updateNodeSettings")
    public ResponseEntity<ApiResponse<NodeManagement>> updateNodeSettings(@RequestBody NodeManagement node) {
        // 仅示例：尝试按 nodeId 更新；如有更复杂字段，请在 mapper 中扩展
        if (node.getNodeId() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "nodeId is required"));
        }
        nodeManagementMapper.updateNodeFromK8s(node);
        NodeManagement refreshed = nodeManagementMapper.getNodeById(node.getNodeId());
        return ResponseEntity.ok(ApiResponse.ok(refreshed));
    }

    /**
     * 节点资源指标（按 nodeId 查询）。
     */
    @GetMapping("/nodeMetrics/{nodeId}")
    public ResponseEntity<ApiResponse<NodeManagement>> nodeMetrics(@PathVariable Integer nodeId) {
        NodeManagement data = nodeManagementMapper.getNodeById(nodeId);
        if (data == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "not found"));
        }
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /**
     * 网络拓扑聚合（节点+边），用于前端 networkTopology。
     */
    @GetMapping("/networkTopology")
    public ResponseEntity<ApiResponse<Map<String, Object>>> networkTopology() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("nodes", nodeManagementMapper.networkConstruction());
        List<EdgeManagement> links = edgeManagementMapper.links();
        payload.put("links", links);
        // 与前端字段保持一致，保留 links 兼容旧调用
        payload.put("edges", links);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

}