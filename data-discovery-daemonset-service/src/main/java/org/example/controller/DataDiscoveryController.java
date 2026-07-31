//package org.example.controller;
//
//import org.example.model.FileData; // 导入 FileData 实体类，它用于API的请求和响应
//import org.example.service.FileDiscoveryService; // 导入 FileDiscoveryService，它包含实际的文件扫描逻辑
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.util.List;
//
///**
// * REST 控制器，用于暴露文件发现 API。
// * 此控制器部署在 data-provider-service DaemonSet 中，负责响应其他服务的文件元数据查询请求。
// */
//@RestController // 标记这是一个Spring REST控制器，所有方法都默认返回JSON/XML等数据
//@RequestMapping("/data-discovery") // 定义此控制器下所有API的根路径为 "/data-discovery"
//public class DataDiscoveryController {
//
//    private static final Logger log = LoggerFactory.getLogger(DataDiscoveryController.class);
//
//    // 自动注入 FileDiscoveryService，Spring会负责创建其实例并注入到这里
//    @Autowired
//    private FileDiscoveryService fileDiscoveryService;
//
//    /**
//     * 处理 HTTP GET 请求，路径为 /data-discovery/files。
//     * 用于获取本地 /data 目录下的文件元数据列表。
//     *
//     * @param lastModifiedSinceMillis 可选参数。如果提供，仅返回在此时间戳（Unix 毫秒）之后修改的文件。
//     *                                如果为 null，则返回所有文件。
//     * @return 包含文件元数据的列表（JSON 格式）。
//     */
//    @GetMapping("/files") // 定义此方法处理 GET /data-discovery/files 请求
//    public List<FileData> listFiles(
//            @RequestParam(required = false) Long lastModifiedSinceMillis) { // @RequestParam 用于接收URL查询参数
//        log.info("Received request for file discovery. lastModifiedSinceMillis: {}", lastModifiedSinceMillis);
//
//        // 调用 FileDiscoveryService 来执行文件扫描逻辑
//        List<FileData> files = fileDiscoveryService.discoverLocalFiles(lastModifiedSinceMillis);
//
//        log.info("Returning {} discovered files.", files.size());
//        // Spring 会自动将 List<FileData> 对象转换为 JSON 格式并作为 HTTP 响应体返回
//        return files;
//    }
//}


package org.example.controller;

import org.example.model.FileData;
import org.example.service.FileDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST 控制器，用于暴露文件发现 API
 * 注意：此服务现在主动写入数据库，HTTP API 主要用于手动查询和监控
 */
@RestController
@RequestMapping("/data-discovery")
public class DataDiscoveryController {

    private static final Logger log = LoggerFactory.getLogger(DataDiscoveryController.class);

    @Autowired
    private FileDiscoveryService fileDiscoveryService;

    @Value("${node.name:unknown}")
    private String nodeName;

    @Value("${file.discovery.data-directory:/data}")
    private String dataDirectory;

    @Value("${file.transfer.connect-timeout-ms:5000}")
    private int transferConnectTimeoutMs;

    @Value("${file.transfer.read-timeout-ms:600000}")
    private int transferReadTimeoutMs;

    /**
     * 获取文件列表（HTTP API，向后兼容）
     */
    @GetMapping("/files")
    public List<FileData> listFiles(
            @RequestParam(required = false) Long lastModifiedSinceMillis) {
        log.info("收到文件列表查询请求，lastModifiedSinceMillis: {}", lastModifiedSinceMillis);

        List<FileData> files = fileDiscoveryService.discoverLocalFiles(lastModifiedSinceMillis);

        log.info("返回 {} 个文件", files.size());
        return files;
    }

    /**
     * 健康检查端点
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("nodeName", nodeName);
        health.put("dataDirectory", dataDirectory);
        health.put("timestamp", System.currentTimeMillis());

        // 检查数据目录
        Path dataPath = Paths.get(dataDirectory);
        health.put("dataDirectoryExists", Files.exists(dataPath));
        health.put("dataDirectoryReadable", Files.isReadable(dataPath));

        return health;
    }

    /**
     * 统计信息端点
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<FileData> files = fileDiscoveryService.discoverLocalFiles(null);

        Map<String, Long> typeCount = files.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        FileData::getFileType,
                        java.util.stream.Collectors.counting()
                ));

        long totalSize = files.stream()
                .mapToLong(FileData::getSizeBytes)
                .sum();

        Map<String, Object> stats = new HashMap<>();
        stats.put("nodeName", nodeName);
        stats.put("totalFiles", files.size());
        stats.put("totalSizeBytes", totalSize);
        stats.put("totalSizeMB", totalSize / (1024.0 * 1024.0));
        stats.put("filesByType", typeCount);
        stats.put("timestamp", System.currentTimeMillis());

        return stats;
    }

    /**
     * 手动触发扫描（用于测试）
     */
    @GetMapping("/scan")
    public Map<String, Object> triggerScan() {
        log.info("收到手动触发扫描请求");

        try {
            fileDiscoveryService.scanAndSyncFiles();

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "文件扫描已触发");
            result.put("timestamp", System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            log.error("手动扫描失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", e.getMessage());
            return result;
        }
    }

    /**
     * 文件上传接口 —— 供 practice-server saveAll 物理迁移时将数据推送到目标节点。
     * URL: POST /data-discovery/upload
     * 参数:
     *   file - multipart 文件体
     *   path - 目标相对路径（相对于 dataDirectory），例如 catdog.npz 或 subdir/file.npz
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("path") String relativePath) {

        Path target = Paths.get(dataDirectory).resolve(relativePath).normalize();
        // 安全检查：防止路径穿越
        if (!target.startsWith(Paths.get(dataDirectory).normalize())) {
            log.warn("拒绝路径穿越上传请求: {}", relativePath);
            Map<String, Object> err = new HashMap<>(); err.put("error", "path traversal rejected");
            return ResponseEntity.badRequest().body(err);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            log.info("文件上传成功: {} ({} bytes)", target, file.getSize());
            Map<String, Object> result = new HashMap<>();
            result.put("status", "ok");
            result.put("path", relativePath);
            result.put("size", file.getSize());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("文件上传失败: {}", relativePath, e);
            Map<String, Object> err = new HashMap<>(); err.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /**
     * 目标节点直接从源节点流式拉取文件。控制面只发送编排请求，
     * 不再把整个数据文件装进 practice-server 的堆内存。
     */
    @PostMapping(value = "/copy-from", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> copyFrom(@RequestBody Map<String, Object> request) {
        String sourceUrl = request.get("sourceUrl") == null ? null : String.valueOf(request.get("sourceUrl"));
        String relativePath = request.get("path") == null ? null : String.valueOf(request.get("path"));
        Long expectedSize = parseLong(request.get("expectedSize"));

        Map<String, Object> result = new HashMap<>();
        if (sourceUrl == null || sourceUrl.isEmpty() || relativePath == null || relativePath.isEmpty()) {
            result.put("error", "sourceUrl and path are required");
            return ResponseEntity.badRequest().body(result);
        }

        Path root = Paths.get(dataDirectory).toAbsolutePath().normalize();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            result.put("error", "path traversal rejected");
            return ResponseEntity.badRequest().body(result);
        }

        URI sourceUri;
        try {
            sourceUri = URI.create(sourceUrl);
            if (!"http".equalsIgnoreCase(sourceUri.getScheme())) {
                throw new IllegalArgumentException("only http source URLs are allowed");
            }
        } catch (Exception e) {
            result.put("error", "invalid sourceUrl: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }

        HttpURLConnection connection = null;
        Path tempFile = null;
        try {
            Files.createDirectories(target.getParent());
            tempFile = target.resolveSibling(target.getFileName() + ".part-" + UUID.randomUUID());

            connection = (HttpURLConnection) sourceUri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(Math.max(1000, transferConnectTimeoutMs));
            connection.setReadTimeout(Math.max(1000, transferReadTimeoutMs));
            connection.setInstanceFollowRedirects(false);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("source returned HTTP " + status);
            }

            long bytesCopied;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[1024 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                }
                bytesCopied = total;
            }

            if (expectedSize != null && expectedSize >= 0 && bytesCopied != expectedSize) {
                throw new IOException("size mismatch: expected=" + expectedSize + ", actual=" + bytesCopied);
            }

            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile = null;

            result.put("status", "ok");
            result.put("path", relativePath);
            result.put("size", bytesCopied);
            log.info("节点间流式复制成功: {} -> {} ({} bytes)", sourceUrl, target, bytesCopied);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("节点间流式复制失败: {} -> {}: {}", sourceUrl, relativePath, e.getMessage());
            result.put("error", e.getMessage());
            return ResponseEntity.status(502).body(result);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException cleanupError) {
                    log.warn("清理临时文件失败 {}: {}", tempFile, cleanupError.getMessage());
                }
            }
        }
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 文件删除接口 —— 供 practice-server saveAll 物理迁移后删除源节点旧文件。
     * URL: DELETE /data-discovery/delete/**  （支持多级路径，如 dataset/yelp/npz/yelp.npz）
     */
    @DeleteMapping("/delete/**")
    public ResponseEntity<Map<String, Object>> deleteFile(HttpServletRequest request) {
        String prefix = "/data-discovery/delete/";
        String uri = request.getRequestURI();
        String filename = uri.contains(prefix) ? uri.substring(uri.indexOf(prefix) + prefix.length()) : "";
        // 与 download 端点保持一致：filePath 已含根目录名（如 "dataset/..."），从 "/" 解析而非从 dataDirectory 解析
        // 若用 Paths.get(dataDirectory).resolve(filename) 会产生三重前缀（/dataset/dataset/dataset/...）
        Path filePath = Paths.get("/").resolve(filename).normalize();

        if (!filePath.startsWith(Paths.get(dataDirectory).normalize())) {
            log.warn("拒绝路径穿越删除请求: {}", filename);
            Map<String, Object> err = new HashMap<>(); err.put("error", "path traversal rejected");
            return ResponseEntity.badRequest().body(err);
        }
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            log.info("删除文件: {} → {}", filename, deleted ? "成功" : "文件不存在");
            Map<String, Object> result = new HashMap<>();
            result.put("status", "ok");
            result.put("deleted", deleted);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("删除文件失败: {}", filename, e);
            Map<String, Object> err = new HashMap<>(); err.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    /**
     * 文件下载接口 —— 供 K8s Job init container(wget) 拉取原始数据文件。
     * URL: GET /data-discovery/download/**
     * 对应 K8sJobFactory 中的 dataSourceUrl。
     */
    @GetMapping(value = "/download/**", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> downloadFile(HttpServletRequest request) {
        // 提取 /download/ 之后的完整路径（含子目录和扩展名）
        String filename = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String bestPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        filename = new AntPathMatcher().extractPathWithinPattern(bestPattern, filename);

        // filename 已含 dataDirectory 名称（如 "dataset/catdog/..."），从根路径解析得到绝对路径
        Path filePath = Paths.get("/").resolve(filename).normalize();

        // 安全检查：防止路径穿越，确保仍在 dataDirectory 目录下
        if (!filePath.startsWith(Paths.get(dataDirectory).normalize())) {
            log.warn("拒绝路径穿越请求: {}", filename);
            return ResponseEntity.badRequest().build();
        }

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            log.warn("文件不存在: {}", filePath);
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(filePath);
        log.info("下载文件: {} → {}", filename, filePath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filePath.getFileName() + "\"")
                .body(resource);
    }
}
