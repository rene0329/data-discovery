package org.example.factory;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.NodeManagement;
import org.example.entity.TrainingProfile;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.TrainingProfileMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class K8sJobFactory {

    private final Map<String, KubernetesClient> clusterClients = new ConcurrentHashMap<>();
    private final String kubeconfigPath;
    private final NodeManagementMapper nodeManagementMapper;
    private final TrainingProfileMapper trainingProfileMapper;
    private final String clusterDomain;
    private final String initContainerImage;
    private final String mainContainerImage;
    private final String discoveryService;
    private final String discoveryNamespace;
    private final int discoveryPort;
    private final String wgetLimitRate;

    /** 路径级限速: master-141 -> master-40 */
    @Value("${dispatch.job.curl.limit-rate.141-to-40:}")
    private String wgetLimitRate141To40;

    /** 路径级限速: master-141 -> master-215 */
    @Value("${dispatch.job.curl.limit-rate.141-to-215:}")
    private String wgetLimitRate141To215;

    /** 路径级限速: master-40 -> master-215 */
    @Value("${dispatch.job.curl.limit-rate.40-to-215:}")
    private String wgetLimitRate40To215;

    @Value("${dispatch.scheduler.weight.cpuFreePct:0.5}")
    private double weightCpuFreePct;

    @Value("${dispatch.scheduler.weight.memFreePct:0.4}")
    private double weightMemFreePct;

    @Value("${dispatch.scheduler.weight.sameClusterBonus:1.0}")
    private double sameClusterBonus;

    @Value("${dispatch.scheduler.weight.crossClusterPenalty:0.5}")
    private double crossClusterPenalty;

    @Value("${dispatch.scheduler.weight.datasetPenalty:0.05}")
    private double datasetPenalty;

    /** 数据亲和性加分：候选节点 = 数据所在源节点时额外加分，确保亲和性调度稳定选中源节点 */
    @Value("${dispatch.scheduler.weight.dataAffinityBonus:5.0}")
    private double dataAffinityBonus;

    @Value("${dispatch.scheduler.threshold.cpuHeadroom:0.1}")
    private double cpuHeadroom;

    @Value("${dispatch.scheduler.threshold.memHeadroom:0.1}")
    private double memHeadroom;

    // <-- 这里是 CandidateNode 的定义，已添加回来
    private static class CandidateNode {
        private final String name;
        private final String clusterId;
        private final double maxCpu;
        private final double maxMemGi;
        private final double cpuFree;
        private final double memFreeGi;
        private final int datasetCount;
        public CandidateNode(String name, String clusterId, double maxCpu, double maxMemGi, double cpuFree, double memFreeGi, int datasetCount) {
            this.name = name; this.clusterId = clusterId; this.maxCpu = maxCpu; this.maxMemGi = maxMemGi; this.cpuFree = cpuFree; this.memFreeGi = memFreeGi; this.datasetCount = datasetCount;
        }
        public String getName() { return name; }
        public String getClusterId() { return clusterId; }
        public double getMaxCpu() { return maxCpu; }
        public double getMaxMemGi() { return maxMemGi; }
        public double getCpuFree() { return cpuFree; }
        public double getMemFreeGi() { return memFreeGi; }
        public int getDatasetCount() { return datasetCount; }
    }
    // <-- CandidateNode 定义结束

    @Autowired
    public K8sJobFactory(
            @Value("${dispatch.kubeconfig.path:C:/Users/xuty/.kube/config}") String kubeconfigPath,
            NodeManagementMapper nodeManagementMapper,
            TrainingProfileMapper trainingProfileMapper,
            @Value("${dispatch.cluster.domain:cluster.local}") String clusterDomain,
            @Value("${dispatch.job.image.init:busybox:1.35}") String initContainerImage,
            @Value("${dispatch.job.image.main:python:3.9-slim}") String mainContainerImage,
            @Value("${dispatch.data-discovery.service:data-discovery-svc}") String discoveryService,
            @Value("${dispatch.data-discovery.namespace:default}") String discoveryNamespace,
            @Value("${dispatch.data-discovery.port:8080}") int discoveryPort,
            @Value("${dispatch.job.curl.limit-rate.default:}") String wgetLimitRate
    ) {
        this.kubeconfigPath = kubeconfigPath;
        this.nodeManagementMapper = nodeManagementMapper;
        this.trainingProfileMapper = trainingProfileMapper;
        this.clusterDomain = clusterDomain;
        this.initContainerImage = initContainerImage;
        this.mainContainerImage = mainContainerImage;
        this.discoveryService = discoveryService;
        this.discoveryNamespace = discoveryNamespace;
        this.discoveryPort = discoveryPort;
        this.wgetLimitRate = wgetLimitRate;
    }

    @PostConstruct
    public void init() {
        // --- 优先尝试 In-Cluster 配置（仅当检测到 K8s 环境变量时） ---
        boolean inClusterEnv = System.getenv("KUBERNETES_SERVICE_HOST") != null;
        if (inClusterEnv) {
            try {
                KubernetesClient inClusterClient = new DefaultKubernetesClient();
                String inClusterContextName;
                try {
                    inClusterContextName = inClusterClient.getConfiguration().getCurrentContext().getName();
                } catch (Exception e) {
                    inClusterContextName = "in-cluster-default";
                }
                clusterClients.put(inClusterContextName, inClusterClient);
                log.info("K8sJobFactory 成功初始化 In-Cluster 客户端 ({})。", inClusterContextName);
            } catch (KubernetesClientException e) {
                log.warn("K8sJobFactory 无法初始化 In-Cluster 客户端（{}），将尝试加载外部 kubeconfig 文件。", e.getMessage());
            } catch (Exception e) {
                log.error("K8sJobFactory 尝试初始化 In-Cluster 客户端时发生未知错误，将尝试加载外部 kubeconfig 文件。", e);
            }
        } else {
            log.info("未检测到 K8s In-Cluster 环境变量，跳过 In-Cluster，直接加载外部 kubeconfig。");
        }

        // --- 回退到加载外部 kubeconfig 文件 ---
        if (clusterClients.isEmpty()) {
            log.info("回退：尝试从外部文件加载 kubeconfig: {}", kubeconfigPath);
            try (InputStream is = new FileInputStream(kubeconfigPath)) {
                Yaml yaml = new Yaml();
                Map<String, Object> kubeconfig = yaml.load(is);
                List<Map<String, Object>> contexts = (List<Map<String, Object>>) kubeconfig.get("contexts");

                if (contexts == null || contexts.isEmpty()) {
                    log.warn("外部 kubeconfig 文件中没有找到任何 contexts。");
                    return;
                }

                for (Map<String, Object> contextMeta : contexts) {
                    String contextName = (String) contextMeta.get("name");
                    try {
                        Config config = Config.fromKubeconfig(null, yaml.dump(kubeconfig), contextName);
                        KubernetesClient client = new DefaultKubernetesClient(config);
                        clusterClients.put(contextName, client);
                        log.info("成功初始化并添加集群'{}'的Kubernetes客户端。", contextName);
                    } catch (Exception e) {
                        log.error("初始化集群 '{}' 的客户端时失败。", contextName, e);
                    }
                }
                log.info("K8sJobFactory 从外部文件初始化完成，共管理 {} 个集群的客户端。", clusterClients.size());
            } catch (FileNotFoundException e) {
                log.error("外部 Kubeconfig 文件未找到: {}。确保路径正确或已部署在K8s集群中。", kubeconfigPath, e);
            } catch (Exception e) {
                log.error("加载和解析外部 Kubeconfig 时发生错误。", e);
            }
        }

        if (clusterClients.isEmpty()) {
            log.error("K8sJobFactory 初始化失败：既无法使用 In-Cluster 认证，也无法加载外部 kubeconfig 文件。没有可用的 Kubernetes 客户端。");
        }
    }

    public JobCreationResult createDataProcessingJob(String jobName,
                                                     String sourceNodeName,
                                                     String dataFileName,
                                                     String dataFilePath,
                                                     String overrideTargetNode,
                                                     Double cpuRequest,
                                                     Double memoryRequest) {

        TrainingProfile profile = resolveTrainingProfile(dataFileName);
        double effectiveCpu = cpuRequest != null ? cpuRequest : (profile != null && profile.getDefaultCpu() != null ? profile.getDefaultCpu() : 0.5);
        double effectiveMem = memoryRequest != null ? memoryRequest : (profile != null && profile.getDefaultMem() != null ? profile.getDefaultMem() : 1.0);
        String selectedMainImage = (profile != null && profile.getImage() != null && !profile.getImage().isEmpty()) ? profile.getImage() : mainContainerImage;
        String selectedEntrypoint = (profile != null && profile.getEntrypoint() != null && !profile.getEntrypoint().isEmpty()) ? profile.getEntrypoint() : "/app/train.py";
        String selectedDataPath = renderDataPath(profile != null ? profile.getDataPathTemplate() : null, dataFileName);
        String selectedTaskType = profile != null ? profile.getTaskType() : inferTaskType(dataFileName);
        String selectedModelType = profile != null ? profile.getModelType() : "default";

        NodeManagement sourceNodeInfo = nodeManagementMapper.getNodeByName(sourceNodeName);
        if (sourceNodeInfo == null) {
            throw new IllegalStateException("在数据库中找不到源节点信息: " + sourceNodeName);
        }
        String sourceClusterId = sourceNodeInfo.getCluster();

        String overrideTargetClusterId = null;
        if (overrideTargetNode != null && !overrideTargetNode.isEmpty()) {
            NodeManagement targetNodeInfo = nodeManagementMapper.getNodeByName(overrideTargetNode);
            if (targetNodeInfo == null) {
                throw new IllegalStateException("在数据库中找不到目标节点信息: " + overrideTargetNode);
            }
            overrideTargetClusterId = targetNodeInfo.getCluster();
        }

        if (clusterClients.isEmpty()) {
            throw new IllegalStateException("没有任何可用的Kubernetes集群客户端，无法调度Job。");
        }

        // 若 DB 中 cluster 字段为空，单集群环境下自动使用唯一可用客户端的 key
        String fallbackClusterId = clusterClients.size() == 1 ? clusterClients.keySet().iterator().next() : null;
        if ((sourceClusterId == null || sourceClusterId.isEmpty()) && fallbackClusterId != null) {
            sourceClusterId = fallbackClusterId;
        }
        if ((overrideTargetClusterId == null || overrideTargetClusterId.isEmpty()) && overrideTargetNode != null && !overrideTargetNode.isEmpty() && fallbackClusterId != null) {
            overrideTargetClusterId = fallbackClusterId;
        }

        CandidateNode bestNode;
        if (overrideTargetNode != null && !overrideTargetNode.isEmpty() && overrideTargetClusterId != null && !overrideTargetClusterId.isEmpty()) {
            log.info("调度决策被覆盖: Job '{}' 将被强制调度到集群 '{}' 的节点 '{}'", jobName, overrideTargetClusterId, overrideTargetNode);
            bestNode = new CandidateNode(overrideTargetNode, overrideTargetClusterId, 0.0, 0.0, 0.0, 0.0, 0);
        } else {
            log.info("===== [智能调度流程开始] Job: {} =====", jobName);
            List<CandidateNode> allAvailableNodes = gatherAvailableNodes(effectiveCpu, effectiveMem);
            if (allAvailableNodes.isEmpty()) {
                throw new IllegalStateException("在所有已知的集群中，没有找到任何可用的'compute'角色的节点。");
            }
            log.info("汇集阶段完成: 从 {} 个集群中找到 {} 个可用的计算节点。", clusterClients.size(), allAvailableNodes.size());
            bestNode = selectBestNode(allAvailableNodes, sourceClusterId, sourceNodeName);
            if (bestNode == null) {
                throw new IllegalStateException("决策阶段失败: 无法从可用节点中选择一个最佳节点。");
            }
            log.info("决策阶段完成: 选择节点 '{}' (位于集群 '{}') 来运行Job。", bestNode.getName(), bestNode.getClusterId());
        }

        KubernetesClient targetClient = clusterClients.get(bestNode.getClusterId());
        if (targetClient == null) {
            throw new IllegalStateException("找不到目标集群 '" + bestNode.getClusterId() + "' 的客户端。");
        }

        // data-discovery 使用 hostNetwork: true，Pod IP = 节点 IP，直接用 internalIp 访问，不依赖 DNS
        // 下载路径使用 DB 中的 filePath（完整宿主机路径），去掉 dataDirectory 前缀得到相对路径
        String sourceIp = sourceNodeInfo.getInternalIp();
        if (sourceIp == null || sourceIp.isEmpty()) {
            throw new IllegalStateException("源节点 " + sourceNodeName + " 未配置 internalIp，无法构建数据下载 URL");
        }
        // filePath 形如 /dataset/catdog/npz/catdog.npz，去掉开头的 / 作为 URL 路径中的相对部分
        String downloadRelPath = (dataFilePath != null && !dataFilePath.isEmpty())
                ? dataFilePath.replaceAll("^/+", "")
                : dataFileName;
        String dataSourceUrl = String.format("http://%s:%d/data-discovery/download/%s", sourceIp, discoveryPort, downloadRelPath);
        log.info("执行阶段: 将在集群 '{}' 中创建Job，数据源URL为: {}", bestNode.getClusterId(), dataSourceUrl);

        Job jobToCreate = new JobBuilder()
                .withApiVersion("batch/v1")
                .withKind("Job")
                .withNewMetadata()
                .withName(jobName)
                .withNamespace("default")
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("app", jobName)
                .endMetadata()
                .withNewSpec()
                .addNewVolume()
                .withName("shared-data")
                .withNewEmptyDir().endEmptyDir()
                .endVolume()

                .addNewInitContainer()
                .withName("data-transfer-container")
                .withImage(initContainerImage)
                .withCommand("sh", "-c", buildWgetCommand(selectedDataPath, dataSourceUrl, resolveLimitRate(sourceNodeName, bestNode.getName())))
                .addNewVolumeMount()
                .withName("shared-data")
                .withMountPath("/data")
                .endVolumeMount()
                .endInitContainer()

                .addNewContainer()
                .withName("processing-container")
                .withImage(selectedMainImage)
                .withCommand("python", selectedEntrypoint)
                .addNewEnv().withName("TASK_TYPE").withValue(selectedTaskType).endEnv()
                .addNewEnv().withName("MODEL_TYPE").withValue(selectedModelType).endEnv()
                .addNewEnv().withName("DATA_NAME").withValue(dataFileName).endEnv()
                .addNewEnv().withName("DATA_PATH").withValue(selectedDataPath).endEnv()
                .addNewEnv().withName("OUTPUT_DIR").withValue("/data/output").endEnv()
                .withNewResources()
                .addToRequests("cpu", new Quantity(String.valueOf(effectiveCpu)))
                .addToRequests("memory", new Quantity(String.valueOf(effectiveMem) + "Gi"))
                .endResources()
                .addNewVolumeMount()
                .withName("shared-data")
                .withMountPath("/data")
                .endVolumeMount()
                .endContainer()

                .withRestartPolicy("Never")
                .withNodeName(bestNode.getName())
                .endSpec()
                .endTemplate()
                .withBackoffLimit(2)
                .endSpec()
                .build();

        return new JobCreationResult(jobToCreate, targetClient, bestNode.getName());
    }

    private TrainingProfile resolveTrainingProfile(String datasetName) {
        try {
            TrainingProfile exact = trainingProfileMapper.findByDatasetName(datasetName);
            if (exact != null) {
                return exact;
            }
            String taskType = inferTaskType(datasetName);
            return trainingProfileMapper.findDefaultByTaskType(taskType);
        } catch (Exception e) {
            log.warn("训练配置路由失败，使用默认镜像。dataset={}, err={}", datasetName, e.getMessage());
            return null;
        }
    }

    private String inferTaskType(String datasetName) {
        String n = datasetName == null ? "" : datasetName.toLowerCase();
        if (n.contains("rating") || n.contains("recsys") || n.contains("recommend")) {
            return "recsys";
        }
        if (n.contains("emotion") || n.contains("sentiment") || n.endsWith(".csv") || n.contains("text")) {
            return "text";
        }
        if (n.contains("cat") || n.contains("dog") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.contains("image")) {
            return "image";
        }
        return "text";
    }

    private String renderDataPath(String template, String dataFileName) {
        String safeName = dataFileName == null || dataFileName.isEmpty() ? "dataset.bin" : dataFileName;
        String t = (template == null || template.isEmpty()) ? "/data/{dataset}" : template;
        return t.replace("{dataset}", safeName);
    }

    /**
     * 根据源节点和目标节点解析应使用的 --limit-rate 字符串。
     * 优先级: 路径级配置 > 全局配置 > 不限速。
     */
    private String resolveLimitRate(String srcNode, String destNode) {
        if (srcNode != null && destNode != null) {
            if (srcNode.contains("141") && destNode.contains("40") && isValidRate(wgetLimitRate141To40))   return wgetLimitRate141To40;
            if (srcNode.contains("141") && destNode.contains("215") && isValidRate(wgetLimitRate141To215)) return wgetLimitRate141To215;
            if (srcNode.contains("40")  && destNode.contains("215") && isValidRate(wgetLimitRate40To215))  return wgetLimitRate40To215;
        }
        return isValidRate(wgetLimitRate) ? wgetLimitRate : null;
    }

    private boolean isValidRate(String rate) {
        return rate != null && !rate.isEmpty() && !"0".equals(rate);
    }

    /**
     * 将 "--limit-rate" 字符串（如 "5m"、"8m"、"100k"）解析为字节/秒整数。
     * 支持后缀 k/m/g（大小写均可），无法解析时返回 0。
     */
    private static long parseLimitRateToBytes(String rate) {
        if (rate == null || rate.isEmpty()) return 0;
        String r = rate.trim().toLowerCase();
        try {
            if (r.endsWith("g")) return Long.parseLong(r.substring(0, r.length() - 1)) * 1024L * 1024 * 1024;
            if (r.endsWith("m")) return Long.parseLong(r.substring(0, r.length() - 1)) * 1024L * 1024;
            if (r.endsWith("k")) return Long.parseLong(r.substring(0, r.length() - 1)) * 1024L;
            return Long.parseLong(r);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 根据文件大小和源/目节点的限速配置，估算传输所需毫秒数。
     * 用于"原地调度"场景下替换固定基础时间。
     */
    public long calculateBaselineMs(long fileSizeBytes, String srcNode, String dstNode) {
        String rate = resolveLimitRate(srcNode, dstNode);
        long rateBytes = parseLimitRateToBytes(rate);
        return rateBytes > 0 ? (fileSizeBytes * 1000L / rateBytes) : 0;
    }

    /**
     * 用指定速率（如 "100m"）估算 fileSizeBytes 字节本地读取所需毫秒数。
     * 用于原地调度基础时间计算，模拟本地磁盘 I/O 而非网络传输速率。
     */
    public long calculateBaselineMsWithRate(long fileSizeBytes, String rate) {
        long rateBytes = parseLimitRateToBytes(rate);
        return rateBytes > 0 ? (fileSizeBytes * 1000L / rateBytes) : 0;
    }

    /**
     * 构建 init container curl 命令（curlimages/curl 镜像，原生支持 --limit-rate）。
     * 使用 curl --write-out '%{time_total}' 获取微秒级传输时间，精度 ~1ms，
     * 避免依赖 /proc/uptime（仅 10ms 精度）。
     * 在 stdout 输出 TRANSFER_MS=<ms>，供 Java 从 pod 日志中提取。
     */
    private String buildWgetCommand(String destPath, String srcUrl, String limitRate) {
        String limitRateArg = isValidRate(limitRate) ? " --limit-rate " + limitRate : "";
        return "mkdir -p \"$(dirname '" + destPath + "')\" && "
                + "_t=$(curl -fsSL" + limitRateArg + " -o '" + destPath + "' --write-out '%{time_total}' '" + srcUrl + "') && "
                + "echo \"TRANSFER_MS=$(echo $_t | awk '{printf \"%d\", $1*1000}')\"";
    }

    private List<CandidateNode> gatherAvailableNodes(double cpuRequest, double memoryRequestGi) {
        List<CandidateNode> allNodes = new ArrayList<>();
        // 直接从 DB 查询所有具备计算能力的节点（含双角色节点），不再依赖 K8s label
        List<NodeManagement> computeNodes = nodeManagementMapper.getComputeCapableNodes();
        String singleClusterKey = clusterClients.size() == 1 ? clusterClients.keySet().iterator().next() : null;
        for (NodeManagement nm : computeNodes) {
            String clusterId = nm.getCluster();
            // DB cluster 字段为空时，单集群环境下自动回退到唯一集群
            if (clusterId == null || clusterId.isEmpty()) {
                if (singleClusterKey != null) {
                    clusterId = singleClusterKey;
                } else {
                    continue;
                }
            }
            // 该节点所在集群必须有可用的 K8s 客户端（防止跨集群找不到 kubeconfig）
            if (!clusterClients.containsKey(clusterId)) {
                continue;
            }
            double maxCpu = nm.getMaxCpu() != null ? nm.getMaxCpu() : 0.0;
            double maxMemGi = nm.getMaxMemory() != null ? nm.getMaxMemory() : 0.0;
            double usedCpu = nm.getCurrentCpu() != null ? nm.getCurrentCpu() : 0.0;
            double usedMemGi = nm.getCurrentMemory() != null ? nm.getCurrentMemory() : 0.0;
            double cpuFree = Math.max(0.0, maxCpu - usedCpu);
            double memFreeGi = Math.max(0.0, maxMemGi - usedMemGi);
            boolean cpuOk = cpuFree >= cpuRequest * (1.0 + cpuHeadroom);
            boolean memOk = memFreeGi >= memoryRequestGi * (1.0 + memHeadroom);
            if (cpuOk && memOk) {
                int datasetCount = nm.getNumDataset() != null ? nm.getNumDataset() : 0;
                allNodes.add(new CandidateNode(nm.getNodeName(), clusterId, maxCpu, maxMemGi, cpuFree, memFreeGi, datasetCount));
            }
        }
        return allNodes;
    }

    private double scoreNode(CandidateNode node, String sourceClusterId, String dataSourceNodeName) {
        double base = node.getClusterId().equals(sourceClusterId) ? sameClusterBonus : -crossClusterPenalty;
        double cpuTerm = node.getMaxCpu() > 0 ? weightCpuFreePct * (node.getCpuFree() / node.getMaxCpu()) : 0.0;
        double memTerm = node.getMaxMemGi() > 0 ? weightMemFreePct * (node.getMemFreeGi() / node.getMaxMemGi()) : 0.0;
        double datasetTerm = datasetPenalty * node.getDatasetCount();
        // 数据亲和性加分：候选节点与数据所在节点相同时，给予大权重加分，确保亲和性调度稳定选择数据源节点
        double affinityTerm = (dataSourceNodeName != null && dataSourceNodeName.equals(node.getName())) ? dataAffinityBonus : 0.0;
        return base + cpuTerm + memTerm - datasetTerm + affinityTerm;
    }

    private CandidateNode selectBestNode(List<CandidateNode> availableNodes, String sourceClusterId, String dataSourceNodeName) {
        if (availableNodes.isEmpty()) {
            return null;
        }
        return availableNodes.stream()
                .sorted((a, b) -> Double.compare(scoreNode(b, sourceClusterId, dataSourceNodeName), scoreNode(a, sourceClusterId, dataSourceNodeName)))
                .findFirst()
                .orElse(null);
    }

    public Map<String, KubernetesClient> getClusterClients() {
        return Collections.unmodifiableMap(clusterClients);
    }
}