package org.example.controller.admin;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.*;
import org.example.dto.registration.LegacyHeatUpdate;
import org.example.mapper.DataManagementMapper;
import org.example.service.NetworkTopologyService;
import org.example.mapper.MigrationTaskMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.service.K8sTaskOrchestratorService; // 引入新的后台服务
import org.example.service.NodeAvailability;
import org.example.service.NodeAvailabilityService;
import org.example.service.PublicIpLocationService;
import org.example.vo.NodeManagementVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.example.vo.ApiResponse;
import org.example.vo.PageResult;

@RestController
@RequestMapping("/common")
@Slf4j
public class CommonController {

    @Autowired
    private org.example.mapper.DatasetRegistrationMapper datasetRegistrationMapper;

    private final DataManagementMapper dataManagementMapper;
    private final NodeManagementMapper nodeManagementMapper;
    private final TaskManagementMapper taskManagementMapper;
    private final MigrationTaskMapper migrationTaskMapper;
    private final NetworkTopologyService networkTopologyService;
    private final K8sTaskOrchestratorService k8sTaskOrchestratorService;
    private final RestTemplate restTemplate;
    private final NodeAvailabilityService nodeAvailabilityService;
    private final PublicIpLocationService publicIpLocationService;

    // data-discovery DaemonSet 寻址配置
    @Value("${dispatch.data-discovery.port:8080}")
    private int discoveryPort;

    /** data-discovery 扫描根目录名（与 DaemonSet FILE_DISCOVERY_DATA_DIRECTORY 保持一致）。
     *  用于 upload 时剥离 filePath 中的根目录前缀，避免双重前缀（/dataset/dataset/...）。 */
    @Value("${dispatch.data-discovery.data-directory:/dataset}")
    private String discoveryDataDirectory;

    /** 访问时的 EMA 历史保留系数；越大则单次访问的影响越小。 */
    @Value("${app.heat-update.access-alpha:0.90}")
    private double accessAlpha;

    @Value("${app.heat-update.max-heat:100}")
    private double maxHeat;

    @Value("${app.heat-update.half-life-hours:24}")
    private double heatHalfLifeHours;

    @Value("${app.heat-update.threshold:10}")
    private double heatThreshold;

    @Autowired
    public CommonController(
            DataManagementMapper dataManagementMapper,
            NodeManagementMapper nodeManagementMapper,
            TaskManagementMapper taskManagementMapper,
            MigrationTaskMapper migrationTaskMapper,
            NetworkTopologyService networkTopologyService,
            K8sTaskOrchestratorService k8sTaskOrchestratorService,
            RestTemplate restTemplate,
            NodeAvailabilityService nodeAvailabilityService,
            PublicIpLocationService publicIpLocationService
    ) {
        this.dataManagementMapper = dataManagementMapper;
        this.nodeManagementMapper = nodeManagementMapper;
        this.taskManagementMapper = taskManagementMapper;
        this.migrationTaskMapper = migrationTaskMapper;
        this.networkTopologyService = networkTopologyService;
        this.k8sTaskOrchestratorService = k8sTaskOrchestratorService;
        this.restTemplate = restTemplate;
        this.nodeAvailabilityService = nodeAvailabilityService;
        this.publicIpLocationService = publicIpLocationService;
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
     * 目标节点直接从源节点流式拉取，控制面只传递编排信息。
     * 此方法只负责复制和校验大小，不删除源文件；源文件必须在数据库切换成功后再删除。
     */
    private boolean copyFile(String dataName,
                             String filePath,
                             Long expectedSize,
                             String sourceNode,
                             String targetNode) {
        if (filePath == null || filePath.isEmpty()) {
            log.warn("物理迁移: 数据项 '{}' filePath 为空，无法定位文件，跳过迁移", dataName);
            return false;
        }
        // filePath 为绝对路径（如 /dataset/yelp/npz/yelp.npz），去掉首 / 得到完整相对路径
        String relativePath = filePath.startsWith("/") ? filePath.substring(1) : filePath;
        // upload 端点的 path 参数是相对于 dataDirectory 的路径（如 yelp/npz/yelp.npz）
        // 若直接传 relativePath（含 dataset/ 前缀) 会被 upload 端点再次 resolve 到 dataDirectory 下
        // 导致 /dataset/dataset/yelp/npz/yelp.npz（双重前缀），因此需剥离根目录名
        String dataDirName = Paths.get(discoveryDataDirectory).getFileName().toString(); // "dataset"
        String uploadRelPath = relativePath.startsWith(dataDirName + "/")
                ? relativePath.substring(dataDirName.length() + 1)
                : relativePath;  // 无前缀时兜底，保持原路径
        String encodedRelativePath = encodeRelativePath(relativePath);
        String downloadUrl = discoveryBaseUrl(sourceNode) + "/data-discovery/download/" + encodedRelativePath;
        String copyUrl = discoveryBaseUrl(targetNode) + "/data-discovery/copy-from";
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("sourceUrl", downloadUrl);
            request.put("path", uploadRelPath);
            if (expectedSize != null) {
                request.put("expectedSize", expectedSize);
            }
            ResponseEntity<Map> response = restTemplate.postForEntity(copyUrl, request, Map.class);
            Map body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful()
                    || body == null
                    || !"ok".equals(String.valueOf(body.get("status")))) {
                log.warn("物理复制失败: {} {} -> {}，响应={}", relativePath, sourceNode, targetNode, body);
                return false;
            }
            log.info("物理复制成功并完成大小校验: {} {} -> {} ({} bytes)",
                    relativePath, sourceNode, targetNode, body.get("size"));
            return true;
        } catch (Exception e) {
            log.error("物理复制失败: {} {} -> {}: {}", relativePath, sourceNode, targetNode, e.getMessage());
            return false;
        }
    }

    private boolean deleteFileOnNode(String filePath, String nodeName) {
        if (filePath == null || filePath.isEmpty() || nodeName == null || nodeName.isEmpty()) {
            return false;
        }
        String relativePath = filePath.startsWith("/") ? filePath.substring(1) : filePath;
        String deleteUrl = discoveryBaseUrl(nodeName) + "/data-discovery/delete/" + encodeRelativePath(relativePath);
        try {
            restTemplate.delete(deleteUrl);
            log.info("已删除节点 {} 上的文件: {}", nodeName, relativePath);
            return true;
        } catch (Exception ex) {
            log.warn("删除文件失败（保留为冗余副本）: {} @ {}: {}", relativePath, nodeName, ex.getMessage());
            return false;
        }
    }

    private String encodeRelativePath(String relativePath) {
        return Arrays.stream(relativePath.replace('\\', '/').split("/"))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }


    /**
     * 用户提交数据 (已重构为异步委派模式)
     * 此方法现在非常快速，它会立即返回响应，并将长时间运行的任务交给后台服务处理。
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @PostMapping("/submitData/{currentTaskId}")
    public ResponseEntity<ApiResponse<Integer>> submitData(@PathVariable Integer currentTaskId, @RequestBody List<String> selectedDatas) {
        log.info("接收到调度任务请求，任务ID: {}", currentTaskId);

        if (selectedDatas == null || selectedDatas.isEmpty()) {
            log.warn("提交的数据列表为空，任务 {} 中止。", currentTaskId);
            return ResponseEntity.ok(ApiResponse.ok(0)); // 0 表示没有处理任何数据
        }

        Set<Integer> legacyIds = dataManagementMapper.getAllData().stream()
                .filter(d -> selectedDatas.contains(d.getDataName())).map(DataManagement::getDataId)
                .collect(Collectors.toSet());
        List<RegisteredDataset> registered = datasetRegistrationMapper.listDatasets(null, null).stream()
                .filter(d -> selectedDatas.contains(d.getName()) || legacyIds.contains(d.getLegacyDataId()))
                .collect(Collectors.toList());
        org.example.service.DatasetOperationGuard.lock(datasetRegistrationMapper,
                registered.stream().map(RegisteredDataset::getDatasetId).collect(Collectors.toList()));
        registered.forEach(d -> org.example.service.DatasetOperationGuard.requireIdle(datasetRegistrationMapper, d));

        // 1. (快速) 创建总任务记录，初始状态为 "执行中"
        TaskManagement taskManagement = TaskManagement.builder()
                .taskName("任务")  // 先用占位名，insert 后用 DB 自增 ID 覆盖
                .selectedData(selectedDatas.toString())
                .status("执行中")
                .createTime(LocalDateTime.now())
                .build();
        taskManagementMapper.submitData(taskManagement);
        Integer taskId = taskManagement.getTaskId(); // 获取数据库生成的自增ID
        // 用 DB 自增 ID 更新 taskName，确保 taskName 与 taskId 一致（"任务62" 而非 "任务1741447200"）
        taskManagement.setTaskName("任务" + taskId);
        taskManagementMapper.updateTask(taskManagement);

        // 2. (快速) 先按真实间隔衰减，再向热度上限做一次 EMA，不使用固定加值
        for (String data : selectedDatas) {
            dataManagementMapper.updateDataHeatOnAccess(
                    data, accessAlpha, maxHeat, heatHalfLifeHours, heatThreshold);
        }

        // 3. (核心) 将包含所有复杂逻辑的任务异步委派给后台服务
        //    这个调用会立即返回，不会阻塞当前请求线程。
        org.example.service.DatasetOperationGuard.afterCommit(() ->
                k8sTaskOrchestratorService.executeTask(taskId, selectedDatas));
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
     * 兼容旧入口。旧实现只改数据库、不移动物理文件，容易制造错误位置，
     * 现在统一委托给安全的热敏迁移流程。
     */
    @PutMapping("/save")
    public ResponseEntity<ApiResponse<List<DataManagement>>> save(@RequestBody(required = false) DataManagement ignored) {
        log.warn("调用了旧接口 PUT /common/save，已转交给 /common/saveAll 的安全迁移流程");
        return saveAll("heat");
    }


    /** Legacy bulk writes are replaced by the reviewed v1 storage plan flow. */
    @PostMapping("/saveAll")
    public ResponseEntity<ApiResponse<List<DataManagement>>> saveAll(
            @RequestParam(defaultValue = "heat") String mode) {
        return ResponseEntity.status(409).body(ApiResponse.error(409,
                "请使用数据集页面的存储预览并确认执行：/api/v1/scheduling/storage-plans/preview"));
    }

    @GetMapping("/updateAll")
    public ResponseEntity<ApiResponse<List<DataManagement>>> updateAll() {
        // 重新获取所有数据，返回给前端
        List<DataManagement> allData = dataManagementMapper.getAllData();
//        System.out.println(allData);
        return ResponseEntity.ok(ApiResponse.ok(allData));

    }



    @DeleteMapping("/clearTasks")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearTasks() {
        Map<String, Object> response = new HashMap<>();
        try {
            // 迁移记录引用 task_id；先删除明细，避免清空任务后留下孤立引用。
            migrationTaskMapper.deleteAll();
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
    public ResponseEntity<ApiResponse<DataManagement>> updateDataItem(@RequestBody LegacyHeatUpdate data) {
        if (data.getDataId() == null || data.getDataId() < 1 || data.getDataHeat() == null
                || !Double.isFinite(data.getDataHeat()) || data.getDataHeat() < 0 || data.getDataHeat() > 100) {
            throw new IllegalArgumentException("dataId is required; dataHeat must be between 0 and 100");
        }
        if (dataManagementMapper.updateHeatById(data.getDataId(), data.getDataHeat()) == 0) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "physical dataset not found"));
        }
        DataManagement refreshed = dataManagementMapper.findById(data.getDataId());
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
     * 调度计划列表：返回有调度方案的任务（schedule 非空）。
     */
    @GetMapping("/scheduleList")
    public ResponseEntity<ApiResponse<PageResult<TaskManagement>>> scheduleList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {
        List<TaskManagement> list = taskManagementMapper.listWithSchedule(query);
        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(list, page, pageSize)));
    }

    /**
     * 性能分析数据：返回有 T1/T2/rating 数据的任务。
     */
    @GetMapping("/analysisData")
    public ResponseEntity<ApiResponse<PageResult<TaskManagement>>> analysisData(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize,
            @RequestParam(defaultValue = "") String query) {
        List<TaskManagement> list = taskManagementMapper.listWithAnalysis(query);
        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(list, page, pageSize)));
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
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTask(@RequestBody TaskManagement task) {
        Map<String, Object> resp = new HashMap<>();
        if (task.getTaskId() == null) {
            resp.put("success", false);
            resp.put("message", "taskId is required");
            return ResponseEntity.badRequest().body(ApiResponse.ok(resp));
        }
        // 删除任务时同步清理其迁移明细。AUTO_INCREMENT 只保证唯一递增，
        // 单条删除产生空号是数据库的正常行为，不应复用已有任务的主键。
        migrationTaskMapper.deleteByTaskId(task.getTaskId());
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
        List<EdgeManagement> edgeManagements = networkTopologyService.links();

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
        for (NodeManagement node : nodes) {
            NodeAvailability availability = nodeAvailabilityService.evaluate(node);
            node.setEffectiveStatus(availability.getEffectiveStatus());
            node.setSchedulable(availability.isSchedulable());
            node.setStatusReason(availability.getReason());
        }

        return ResponseEntity.ok(ApiResponse.ok(PageResult.of(nodes, page, pageSize)));
    }

    /**
     * 节点配置更新（示例：更新节点运行时资源或标签字段）。
     */
    @PostMapping("/updateNodeSettings")
    public ResponseEntity<ApiResponse<NodeManagement>> updateNodeSettings(@RequestBody NodeManagement node) {
        // Do not allow stale UI payloads to overwrite Kubernetes identity or live metrics.
        return ResponseEntity.status(410).body(ApiResponse.error(410,
                "use PATCH /api/v1/nodes/{nodeId} to edit displayName, role or labels with version"));
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
    public ResponseEntity<ApiResponse<Map<String, Object>>> networkTopology(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        List<NodeManagement> nodeList = nodeManagementMapper.networkConstruction();
        if (activeOnly) {
            nodeList = nodeList.stream().filter(nodeAvailabilityService::isSchedulable)
                    .collect(Collectors.toList());
        }

        // 构建 nodeId -> nodeName 映射，供边查找
        Map<Integer, String> nodeIdToName = new HashMap<>();
        List<Map<String, Object>> nodePayload = new ArrayList<>();
        int total = nodeList.size();
        double cx = 400, cy = 300, radius = 180;
        for (int i = 0; i < total; i++) {
            NodeManagement node = nodeList.get(i);
            NodeAvailability availability = nodeAvailabilityService.evaluate(node);
            nodeIdToName.put(node.getNodeId(), node.getNodeName());
            // 圆形自动布局，从顶部开始顺时针排列
            double angle = 2 * Math.PI * i / Math.max(total, 1) - Math.PI / 2;
            Map<String, Object> nodeMap = new HashMap<>();
            nodeMap.put("id", node.getNodeName());
            nodeMap.put("nodeId", node.getNodeId());
            nodeMap.put("cluster", node.getCluster());
            nodeMap.put("internalIp", node.getInternalIp());
            nodeMap.put("externalIp", node.getExternalIp());
            nodeMap.put("publicIpLocation", publicIpLocationService.lookup(node.getExternalIp()));
            nodeMap.put("label", node.getNodeName());
            nodeMap.put("x", (int)(cx + radius * Math.cos(angle)));
            nodeMap.put("y", (int)(cy + radius * Math.sin(angle)));
            nodeMap.put("width", 110);
            nodeMap.put("height", 44);
            // cpu: 核心数百分比（currentCpu 单位为核，maxCpu 同单位）
            Double cpuPct = (node.getCurrentCpu() != null && node.getMaxCpu() != null && node.getMaxCpu() > 0)
                    ? Math.round(node.getCurrentCpu() / node.getMaxCpu() * 1000.0) / 10.0 : null;
            nodeMap.put("cpu", cpuPct);
            // disk(内存占用率): currentMemory/maxMemory * 100，单位均为 GB
            Double memPct = (node.getCurrentMemory() != null && node.getMaxMemory() != null && node.getMaxMemory() > 0)
                    ? Math.round(node.getCurrentMemory() / node.getMaxMemory() * 1000.0) / 10.0 : null;
            nodeMap.put("memory", memPct);
            nodeMap.put("disk", memPct); // Deprecated legacy alias; never a measured disk metric.
            nodeMap.put("registrationStatus", node.getRegistrationStatus());
            nodeMap.put("enabled", node.getEnabled());
            nodeMap.put("observedStatus", node.getObservedStatus());
            nodeMap.put("effectiveStatus", availability.getEffectiveStatus());
            nodeMap.put("schedulable", availability.isSchedulable());
            nodeMap.put("statusReason", availability.getReason());
            nodePayload.add(nodeMap);
        }

        // 将 sourceId/targetId 转换为节点名，供前端渲染
        List<EdgeManagement> edgeList = networkTopologyService.links();
        List<Map<String, Object>> edgePayload = new ArrayList<>();
        for (int i = 0; i < edgeList.size(); i++) {
            EdgeManagement edge = edgeList.get(i);
            String sourceName = nodeIdToName.get(edge.getSourceId());
            String targetName = nodeIdToName.get(edge.getTargetId());
            if (sourceName == null || targetName == null) continue;
            Map<String, Object> edgeMap = new HashMap<>();
            edgeMap.put("id", "e-" + edge.getEdgeId());
            edgeMap.put("source", sourceName);
            edgeMap.put("target", targetName);
            edgeMap.put("latency", edge.getLatency() != null ? edge.getLatency() : 0);
            edgeMap.put("bandwidth", edge.getBandwidth() != null ? edge.getBandwidth() : 0);
            edgeMap.put("status", edge.getStatus() != null ? edge.getStatus() : "UNKNOWN");
            NodeManagement sourceNode = nodeList.stream()
                    .filter(node -> node.getNodeId().equals(edge.getSourceId())).findFirst().orElse(null);
            NodeManagement targetNode = nodeList.stream()
                    .filter(node -> node.getNodeId().equals(edge.getTargetId())).findFirst().orElse(null);
            boolean measured = "active".equalsIgnoreCase(edge.getStatus())
                    || "UP".equalsIgnoreCase(edge.getStatus());
            edgeMap.put("active", measured && nodeAvailabilityService.isSchedulable(sourceNode)
                    && nodeAvailabilityService.isSchedulable(targetNode));
            edgePayload.add(edgeMap);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("nodes", nodePayload);
        payload.put("edges", edgePayload);
        return ResponseEntity.ok(ApiResponse.ok(payload));
    }

}
