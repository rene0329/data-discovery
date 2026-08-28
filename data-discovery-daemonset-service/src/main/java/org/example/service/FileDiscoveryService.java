//package org.example.service;
//
//import org.example.model.FileData;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Service;
//
//import java.io.IOException;
//import java.nio.file.*;
//import java.nio.file.attribute.BasicFileAttributes;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Stream;
//import java.security.MessageDigest; // 重新导入 MD5 相关类
//import java.security.NoSuchAlgorithmException;
//import java.io.FileInputStream;
//import java.io.BufferedInputStream;
//import java.util.Base64;
//
//@Service
//public class FileDiscoveryService {
//
//    private static final Logger log = LoggerFactory.getLogger(FileDiscoveryService.class);
//    private static final String DATA_DIRECTORY = "/data"; // 必须与DaemonSet的hostPath挂载路径一致
//
//    public List<FileData> discoverLocalFiles(Long lastModifiedSinceMillis) {
//        List<FileData> fileList = new ArrayList<>();
//        Path dataPath = Paths.get(DATA_DIRECTORY);
//
//        if (!Files.exists(dataPath) || !Files.isDirectory(dataPath)) {
//            log.warn("Data directory {} does not exist or is not a directory. Returning empty list.", DATA_DIRECTORY);
//            return fileList;
//        }
//
//        try (Stream<Path> pathStream = Files.walk(dataPath)) {
//            pathStream
//                    .filter(Files::isRegularFile) // 只处理文件
//                    .forEach(filePath -> {
//                        try {
//                            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
//                            long lastModifiedTimeMillis = attrs.lastModifiedTime().to(TimeUnit.MILLISECONDS);
//
//                            // 实现增量更新逻辑
//                            if (lastModifiedSinceMillis == null || lastModifiedTimeMillis > lastModifiedSinceMillis) {
//                                FileData fileData = new FileData();
//                                fileData.setName(filePath.getFileName().toString());
//                                fileData.setPath(filePath.toString()); // 完整路径
//                                fileData.setSizeBytes(attrs.size());
//                                fileData.setLastModified(lastModifiedTimeMillis);
//
//                                // 文件类型推断
//                                fileData.setFileType(determineFileType(filePath));
//                                // 启用 MD5 哈希计算
//                                fileData.setMd5Hash(calculateMd5(filePath)); // <--- 启用 MD5 计算
//
//                                fileList.add(fileData);
//                            }
//                        } catch (IOException e) {
//                            log.error("Failed to read attributes for file {}: {}", filePath, e.getMessage());
//                        }
//                    });
//        } catch (IOException e) {
//            log.error("Failed to walk data directory {}: {}", DATA_DIRECTORY, e.getMessage());
//        }
//        return fileList;
//    }
//
//    private String determineFileType(Path filePath) {
//        String fileName = filePath.getFileName().toString();
//        String lowerCaseFileName = fileName.toLowerCase();
//        if (lowerCaseFileName.endsWith(".csv")) {
//            return "CSV";
//        } else if (lowerCaseFileName.endsWith(".json")) {
//            return "JSON";
//        } else if (lowerCaseFileName.endsWith(".parquet")) {
//            return "PARQUET";
//        } else if (lowerCaseFileName.endsWith(".txt")) {
//            return "TEXT";
//        } else if (lowerCaseFileName.endsWith(".log")) {
//            return "LOG";
//        }
//        return "UNKNOWN";
//    }
//
//    /**
//     * 计算文件的MD5哈希值。
//     * 注意：此操作会读取文件的全部内容，对大文件可能带来显著的I/O和CPU开销。
//     * 请根据实际文件大小和数量评估 DaemonSet 的资源需求。
//     * @param filePath 文件路径
//     * @return MD5哈希值的Base64编码字符串
//     */
//    private String calculateMd5(Path filePath) {
//        try {
//            MessageDigest md = MessageDigest.getInstance("MD5");
//            try (FileInputStream fis = new FileInputStream(filePath.toFile());
//                 BufferedInputStream bis = new BufferedInputStream(fis)) {
//                byte[] buffer = new byte[8192]; // 缓冲区大小
//                int bytesRead;
//                while ((bytesRead = bis.read(buffer)) != -1) {
//                    md.update(buffer, 0, bytesRead);
//                }
//            }
//            return Base64.getEncoder().encodeToString(md.digest());
//        } catch (NoSuchAlgorithmException | IOException e) {
//            log.error("Failed to calculate MD5 for file {}: {}", filePath, e.getMessage());
//            return null;
//        }
//    }
//}


package org.example.service;

import org.example.entity.DataManagement;
import org.example.entity.DatasetDiscoveryCandidate;
import org.example.entity.NodeManagement;
import org.example.mapper.DataManagementMapper;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.model.FileData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FileDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(FileDiscoveryService.class);

    @Value("${file.discovery.data-directory:/data}")
    private String DATA_DIRECTORY;

    @Value("${node.name:unknown}")
    private String nodeName;

    @Value("${file.discovery.enable-md5:false}")
    private boolean enableMd5;

    @Value("${file.discovery.md5-max-size-mb:100}")
    private long md5MaxSizeMb;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Autowired
    private DataManagementMapper dataManagementMapper;

    @Autowired
    private DatasetRegistrationMapper datasetRegistrationMapper;

    @Autowired
    private NodeManagementMapper nodeManagementMapper;

    private Integer nodeId; // 当前节点的数据库 ID

    /**
     * 初始化：获取当前节点的数据库 ID
     */
    @PostConstruct
    public void init() {
        log.info("初始化 FileDiscoveryService，节点名称: {}, Profile: {}", nodeName, activeProfile);

        try {
            NodeManagement node = nodeManagementMapper.getNodeByName(nodeName);
            if (node == null) {
                log.error("数据库中未找到节点 '{}' 的信息，请确保 NodeSyncService 已运行", nodeName);
                throw new IllegalStateException("Node not registered in database: " + nodeName);
            }
            nodeId = node.getNodeId();
            log.info("成功初始化节点 '{}' (ID: {})", nodeName, nodeId);
        } catch (Exception e) {
            log.error("初始化 FileDiscoveryService 失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 定时扫描并同步文件到数据库（生产环境）
     */
    @Scheduled(fixedRateString = "${file.discovery.scan.interval.ms:60000}")
    public void scanAndSyncFiles() {
        log.info("[定时任务] 开始扫描节点 '{}' 的文件...", nodeName);

        try {
            // 1. 扫描本地文件
            List<FileData> discoveredFiles = scanLocalFiles();
            log.info("扫描完成，发现 {} 个文件", discoveredFiles.size());

            // 2. 同步到数据库
            syncToDatabase(discoveredFiles);

            log.info("[定时任务] 文件同步完成");
        } catch (Exception e) {
            log.error("扫描和同步文件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 扫描本地文件（支持开发环境 Mock）
     */
    private List<FileData> scanLocalFiles() {
        // 开发环境：使用 Mock 数据
        if ("dev".equals(activeProfile)) {
            log.warn("检测到开发环境，使用 Mock 文件数据进行测试");
            return createMockFileData();
        }

        // 生产环境：真实扫描
        return scanRealFiles();
    }

    /**
     * 真实文件扫描逻辑
     */
    private List<FileData> scanRealFiles() {
        List<FileData> fileList = new ArrayList<>();
        Path dataPath = Paths.get(DATA_DIRECTORY);

        if (!Files.exists(dataPath) || !Files.isDirectory(dataPath)) {
            // 不能把“目录不可用”解释为“目录为空”，否则同步逻辑会删除该节点的全部元数据。
            throw new IllegalStateException("数据目录不存在或不是目录: " + DATA_DIRECTORY);
        }

        try (Stream<Path> pathStream = Files.walk(dataPath)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".npz"))
                    .forEach(filePath -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);

                            FileData fileData = new FileData();
                            fileData.setName(filePath.getFileName().toString());
                            fileData.setPath(filePath.toString());
                            fileData.setSizeBytes(attrs.size());
                            fileData.setLastModified(attrs.lastModifiedTime().to(TimeUnit.MILLISECONDS));
                            fileData.setFileType(determineFileType(filePath));

                            if (enableMd5) {
                                fileData.setMd5Hash(calculateMd5(filePath));
                            }

                            fileList.add(fileData);
                        } catch (Exception e) {
                            log.error("处理文件失败 {}: {}", filePath, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("遍历数据目录失败 {}: {}", DATA_DIRECTORY, e.getMessage());
        }

        return fileList;
    }

    /**
     * Mock 数据（仅用于本地开发测试）
     */
    private List<FileData> createMockFileData() {
        List<FileData> mockFiles = new ArrayList<>();

        FileData file1 = new FileData();
        file1.setName("catdog.npz");
        file1.setPath("/data/catdog.npz");
        file1.setSizeBytes(50 * 1024 * 1024L); // 50 MB
        file1.setLastModified(System.currentTimeMillis() - 3600000);
        file1.setFileType("NPZ");
        file1.setMd5Hash("mock-md5-catdog");
        mockFiles.add(file1);

        FileData file2 = new FileData();
        file2.setName("news.npz");
        file2.setPath("/data/news.npz");
        file2.setSizeBytes(20 * 1024 * 1024L); // 20 MB
        file2.setLastModified(System.currentTimeMillis() - 7200000);
        file2.setFileType("NPZ");
        file2.setMd5Hash("mock-md5-news");
        mockFiles.add(file2);

        FileData file3 = new FileData();
        file3.setName("ratings.npz");
        file3.setPath("/data/ratings.npz");
        file3.setSizeBytes(10 * 1024 * 1024L); // 10 MB
        file3.setLastModified(System.currentTimeMillis() - 1800000);
        file3.setFileType("NPZ");
        file3.setMd5Hash("mock-md5-ratings");
        mockFiles.add(file3);

        log.info("创建了 {} 条 Mock NPZ 文件数据", mockFiles.size());
        return mockFiles;
    }

    /**
     * 同步文件到数据库
     */
    private void syncToDatabase(List<FileData> discoveredFiles) {
        // 自动扫描只维护候选文件和可用性，不再直接创建或删除注册数据集。
        Set<String> discoveredPaths = discoveredFiles.stream()
                .map(FileData::getPath)
                .collect(Collectors.toSet());

        List<DatasetDiscoveryCandidate> knownCandidates =
                datasetRegistrationMapper.listCandidates(null, nodeId, false);
        int missingCount = 0;
        for (DatasetDiscoveryCandidate candidate : knownCandidates) {
            if (!discoveredPaths.contains(candidate.getFilePath())
                    && !"MISSING".equals(candidate.getAvailability())) {
                datasetRegistrationMapper.markCandidateAvailability(
                        nodeId, candidate.getFilePath(), "MISSING");
                missingCount++;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int observedCount = 0;
        for (FileData fileData : discoveredFiles) {
            DatasetDiscoveryCandidate candidate = DatasetDiscoveryCandidate.builder()
                    .nodeId(nodeId)
                    .filePath(fileData.getPath())
                    .fileName(fileData.getName())
                    .fileType(fileData.getFileType())
                    .sizeBytes(fileData.getSizeBytes())
                    .checksum(fileData.getMd5Hash())
                    .lastModifiedAt(LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(fileData.getLastModified()),
                            java.time.ZoneId.systemDefault()))
                    .availability("AVAILABLE")
                    .lastSeenAt(now)
                    .build();
            datasetRegistrationMapper.upsertCandidate(candidate);
            observedCount++;
        }

        log.info("候选文件同步完成: 观测 {}, 标记缺失 {}。注册数据集未被自动删除。",
                observedCount, missingCount);
    }

    // ── 数据集元信息映射（按文件名去扩展名后的 baseName 匹配） ─────────────
    private static class DatasetMeta {
        final String fileType;
        final String description;
        final double requiredCpu;
        final double requiredMemory;
        DatasetMeta(String fileType, String description, double cpu, double memory) {
            this.fileType    = fileType;
            this.description = description;
            this.requiredCpu = cpu;
            this.requiredMemory = memory;
        }
    }

    private static final Map<String, DatasetMeta> DATASET_META = new HashMap<String, DatasetMeta>() {{
        put("catdog",    new DatasetMeta("jpg",
                "猫狗数据集是用于图像分类任务的经典数据集，包含大量猫和狗的图片，常用于训练和评估机器学习模型的图像识别能力。数据集广泛用于初学者和研究者的计算机视觉项目",
                1.0, 2.0));
        put("ciao",      new DatasetMeta("txt",
                "Ciao 数据集是从社交网络 Ciao 采集的数据，包含用户的社交关系、评分记录和评论信息。该数据集常用于推荐系统的研究，尤其是社交推荐、用户信任模型和评分预测等领域",
                0.5, 1.0));
        put("epinions",  new DatasetMeta("txt",
                "Epinions 数据集来源于 Epinions 网站，包含用户的社交关系、产品评分和评论信息。它被广泛应用于研究推荐系统，尤其是结合社会网络信息的推荐算法研究，适用于用户信任模型和社交推荐分析",
                0.5, 1.0));
        put("yelp",      new DatasetMeta("txt",
                "Yelp 数据集来自 Yelp 网站，包含商家信息、用户评论、评分和社交关系等数据。它是研究推荐系统和情感分析的重要资源，广泛用于餐馆、酒店等服务行业的推荐算法研究，特别是在多模态数据分析方面有重要应用",
                0.5, 1.0));
        put("nlpcc2013", new DatasetMeta("csv",
                "NLPCC2013 是自然语言处理与中文计算大会的数据集，主要用于各种中文自然语言处理任务的研究和比赛，涵盖了分词、命名实体识别、情感分析等任务",
                0.5, 1.0));
    }};
    // ──────────────────────────────────────────────────────────────────────────

    /** 去掉文件名末尾的扩展名，例如 catdog.npz → catdog */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }

    /**
     * 检查是否需要更新
     */
    private boolean needsUpdate(DataManagement existing, FileData fileData) {
        return existing.getDataSize() == null ||
                existing.getDataSize() != fileData.getSizeBytes() ||
                existing.getLastModifiedTime() == null ||
                existing.getLastModifiedTime().getTime() != fileData.getLastModified() ||
                !Objects.equals(existing.getMd5Hash(), fileData.getMd5Hash());
    }

    /**
     * 创建新的数据库实体：data_name 去掉扩展名，描述/类型/资源按文件名映射
     */
    private DataManagement createEntity(FileData fileData) {
        String baseName = stripExtension(fileData.getName()).toLowerCase();
        DatasetMeta meta = DATASET_META.get(baseName);

        DataManagement entity = new DataManagement();
        entity.setDataName(baseName);
        entity.setFilePath(fileData.getPath());
        entity.setDataSize(fileData.getSizeBytes());
        entity.setLastModifiedTime(new Timestamp(fileData.getLastModified()));
        entity.setFileType(meta != null ? meta.fileType : fileData.getFileType());
        entity.setMd5Hash(fileData.getMd5Hash());
        entity.setDataNodeId(nodeId);
        entity.setDataServer(nodeName);
        entity.setDataStatus(1);
        entity.setDataHeat(0.0);
        entity.setDataCount(0);
        entity.setDataDescription(meta != null ? meta.description : ("Discovered on " + nodeName));
        entity.setRequiredCpu(meta != null ? meta.requiredCpu : 0.0);
        entity.setRequiredMemory(meta != null ? meta.requiredMemory : 0.0);
        entity.setBackupServer(null);
        return entity;
    }

    /**
     * 更新现有实体
     */
    private void updateEntity(DataManagement existing, FileData fileData) {
        existing.setDataSize(fileData.getSizeBytes());
        existing.setLastModifiedTime(new Timestamp(fileData.getLastModified()));
        existing.setFileType(fileData.getFileType());
        existing.setMd5Hash(fileData.getMd5Hash());
    }

    /**
     * HTTP API 支持 - 用于外部查询（可选，主要用于向后兼容或手动查询）
     */
    public List<FileData> discoverLocalFiles(Long lastModifiedSinceMillis) {
        List<FileData> allFiles = scanLocalFiles();

        if (lastModifiedSinceMillis == null) {
            return allFiles;
        }

        return allFiles.stream()
                .filter(f -> f.getLastModified() > lastModifiedSinceMillis)
                .collect(Collectors.toList());
    }

    /**
     * 判断文件类型
     */
    private String determineFileType(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".npz"))     return "NPZ";
        if (fileName.endsWith(".csv"))     return "CSV";
        if (fileName.endsWith(".json"))    return "JSON";
        if (fileName.endsWith(".parquet")) return "PARQUET";
        if (fileName.endsWith(".txt"))     return "TEXT";
        if (fileName.endsWith(".log"))     return "LOG";
        return "UNKNOWN";
    }

    /**
     * 计算 MD5 哈希值
     */
    private String calculateMd5(Path filePath) {
        try {
            long fileSizeBytes = Files.size(filePath);
            long maxSizeBytes = md5MaxSizeMb * 1024 * 1024;

            if (fileSizeBytes > maxSizeBytes) {
                log.debug("跳过大文件 MD5 计算: {} ({} bytes)", filePath, fileSizeBytes);
                return null;
            }

            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(filePath.toFile());
                 BufferedInputStream bis = new BufferedInputStream(fis)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = bis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            return Base64.getEncoder().encodeToString(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            log.error("计算 MD5 失败 {}: {}", filePath, e.getMessage());
            return null;
        }
    }
}
