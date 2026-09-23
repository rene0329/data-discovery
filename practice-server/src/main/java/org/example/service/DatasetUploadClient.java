package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.NodeManagement;
import org.example.exception.RegistrationException;
import org.example.model.FileIntegrityResult;
import org.example.security.access.AccessAuditContext;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.AccessScope;
import org.example.security.access.DatasetAccessAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Streams uploaded files from the control plane to the selected node Agent.
 * The request uses chunked transfer encoding so the file is never copied into
 * the practice-server heap as a byte array.
 */
@Component
public class DatasetUploadClient {
    private static final Logger log = LoggerFactory.getLogger(DatasetUploadClient.class);
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INTERNAL_DATASET_ID = "internal-path";
    private static final String UNVERSIONED = "unversioned";

    private final int discoveryPort;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String dataDirectory;
    private final DatasetAccessAuthorizationService accessAuthorization;
    private final DataTransferAddressResolver transferAddresses;

    @Autowired
    public DatasetUploadClient(
            @Value("${dispatch.data-discovery.port:8080}") int discoveryPort,
            @Value("${app.storage-transfer.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.storage-transfer.read-timeout-ms:2700000}") int readTimeoutMs,
            @Value("${dispatch.data-discovery.data-directory:/dataset}") String dataDirectory,
            DatasetAccessAuthorizationService accessAuthorization,
            DataTransferAddressResolver transferAddresses) {
        this.discoveryPort = discoveryPort;
        this.connectTimeoutMs = Math.max(1000, connectTimeoutMs);
        this.readTimeoutMs = Math.max(1000, readTimeoutMs);
        this.dataDirectory = Paths.get(dataDirectory).toAbsolutePath().normalize().toString();
        this.accessAuthorization = accessAuthorization;
        this.transferAddresses = transferAddresses;
    }

    /** Test and compatibility constructor for unauthenticated discovery-only operations such as scan. */
    public DatasetUploadClient(int discoveryPort, int connectTimeoutMs, int readTimeoutMs,
                               String dataDirectory) {
        this(discoveryPort, connectTimeoutMs, readTimeoutMs, dataDirectory, null);
    }

    /** Test and compatibility constructor without same-site private addresses. */
    public DatasetUploadClient(int discoveryPort, int connectTimeoutMs, int readTimeoutMs,
                               String dataDirectory, DatasetAccessAuthorizationService accessAuthorization) {
        this(discoveryPort, connectTimeoutMs, readTimeoutMs, dataDirectory, accessAuthorization,
                new DataTransferAddressResolver(""));
    }

    public void upload(NodeManagement node, MultipartFile file, String relativePath) {
        upload(node, file, relativePath, null, null, null, null);
    }

    public void upload(NodeManagement node, MultipartFile file, String relativePath,
                       Long datasetId, String datasetVersion, String requestId, String runId) {
        requireAddress(node);
        String absolutePath = absoluteDataPath(relativePath);
        String accessToken = issueScopedToken(node, absolutePath, "WRITE", datasetId,
                datasetVersion, effectiveRequestId(requestId), runId);
        HttpURLConnection connection = null;
        String boundary = "----topic4-" + UUID.randomUUID();
        try {
            URL url = new URL(baseUrl(node) + "/data-discovery/upload");
            connection = open(url, "POST");
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(BUFFER_SIZE);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            setBearer(connection, accessToken);

            try (DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                 InputStream input = file.getInputStream()) {
                writeField(output, boundary, "path", relativePath);
                writeField(output, boundary, "overwrite", "false");
                output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"dataset.npz\"\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                output.write("Content-Type: application/octet-stream\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status == HttpStatus.CONFLICT.value()) {
                throw RegistrationException.conflict("DATASET_FILE_EXISTS",
                        "target dataset file already exists on node " + node.getNodeName());
            }
            if (status < 200 || status >= 300) {
                throw uploadFailure("node Agent rejected upload with HTTP " + status + detail(response));
            }
        } catch (RegistrationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw uploadFailure("failed to stream dataset to node " + node.getNodeName()
                    + ": " + ex.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public void scan(NodeManagement node) {
        requireAddress(node);
        HttpURLConnection connection = null;
        try {
            connection = open(new URL(baseUrl(node) + "/data-discovery/scan"), "GET");
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status < 200 || status >= 300 || response.contains("\"status\":\"error\"")) {
                throw uploadFailure("node Agent scan failed with HTTP " + status + detail(response));
            }
        } catch (RegistrationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw uploadFailure("failed to scan uploaded dataset on node " + node.getNodeName()
                    + ": " + ex.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Compatibility overload. It first measures the source and then makes the
     * target prove that the exact measured bytes arrived. Callers that know the
     * dataset-version authority should use the five-argument overload.
     */
    public FileIntegrityResult copyFrom(NodeManagement source, NodeManagement target,
                                        String absolutePath, Long expectedSize) {
        FileIntegrityResult sourceMeasurement = verify(source, absolutePath, expectedSize, null);
        if (sourceMeasurement.getDigest() == null || sourceMeasurement.getDigest().trim().isEmpty()) {
            throw uploadFailure("source node returned an empty SHA-256 digest");
        }
        Long measuredSize = expectedSize == null ? sourceMeasurement.getSizeBytes() : expectedSize;
        return copyFrom(source, target, absolutePath, measuredSize, sourceMeasurement.getDigest());
    }

    public FileIntegrityResult copyFrom(NodeManagement source, NodeManagement target,
                                        String absolutePath, Long expectedSize,
                                        String expectedSha256) {
        return copyFrom(source, target, absolutePath, expectedSize, expectedSha256,
                null, null, null, null);
    }

    /**
     * Copies a registered dataset replica using a short-lived READ token scoped
     * to the exact source node and absolute file path.
     */
    public FileIntegrityResult copyFrom(NodeManagement source, NodeManagement target,
                                        String absolutePath, Long expectedSize,
                                        String expectedSha256, Long datasetId,
                                        String datasetVersion, String requestId) {
        return copyFrom(source, target, absolutePath, expectedSize, expectedSha256,
                datasetId, datasetVersion, requestId, null);
    }

    public FileIntegrityResult copyFrom(NodeManagement source, NodeManagement target,
                                        String absolutePath, Long expectedSize,
                                        String expectedSha256, Long datasetId,
                                        String datasetVersion, String requestId,
                                        String runId) {
        requireAddress(source);
        requireAddress(target);
        relativeDataPath(absolutePath);
        String effectiveRequestId = effectiveRequestId(requestId);
        String sourceToken = issueScopedToken(source, absolutePath, "READ", datasetId,
                datasetVersion, effectiveRequestId, runId);
        String targetToken = issueScopedToken(target, absolutePath, "COPY", datasetId,
                datasetVersion, effectiveRequestId, runId);
        return copyFrom(source, target, absolutePath, expectedSize, expectedSha256,
                sourceToken, targetToken);
    }

    private FileIntegrityResult copyFrom(NodeManagement source, NodeManagement target,
                                         String absolutePath, Long expectedSize,
                                         String expectedSha256, String sourceToken,
                                         String targetToken) {
        requireAddress(source);
        requireAddress(target);
        requireSha256(expectedSha256);
        if (expectedSize == null || expectedSize < 0) {
            throw RegistrationException.invalid("expected file size is required for a verified copy");
        }
        HttpURLConnection connection = null;
        try {
            // The target Agent pulls from the source: use the source address as seen from the target.
            String sourceUrl = "http://" + transferAddresses.sourceAddress(source, target) + ":" + discoveryPort
                    + "/data-discovery/download/" + encodeAbsolutePath(absolutePath);
            String relativePath = relativeDataPath(absolutePath);
            String body = "{\"sourceUrl\":\"" + jsonEscape(sourceUrl)
                    + "\",\"path\":\"" + jsonEscape(relativePath) + "\""
                    + ",\"expectedSize\":" + expectedSize
                    + ",\"expectedSha256\":\"" + jsonEscape(expectedSha256) + "\""
                    + (sourceToken == null ? "" : ",\"sourceToken\":\""
                    + jsonEscape(sourceToken) + "\"") + "}";
            connection = open(new URL(baseUrl(target) + "/data-discovery/copy-from"), "POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            setBearer(connection, targetToken);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (DataOutputStream output = new DataOutputStream(connection.getOutputStream())) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throw uploadFailure("node-to-node copy failed with HTTP " + status + detail(response));
            }
            FileIntegrityResult result = parseIntegrityResult(response, "node-to-node copy");
            if (!result.isVerified() || result.getSizeBytes() != expectedSize
                    || !normalizeSha256(expectedSha256).equals(normalizeSha256(result.getDigest()))) {
                throw uploadFailure("node-to-node copy returned an unverified target");
            }
            return result;
        } catch (RegistrationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw uploadFailure("failed to copy dataset from " + source.getNodeName()
                    + " to " + target.getNodeName() + ": " + ex.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public FileIntegrityResult verify(NodeManagement node, String absolutePath,
                                      Long expectedSize, String expectedSha256) {
        return verify(node, absolutePath, expectedSize, expectedSha256,
                null, null, null, null);
    }

    public FileIntegrityResult verify(NodeManagement node, String absolutePath,
                                      Long expectedSize, String expectedSha256,
                                      Long datasetId, String datasetVersion,
                                      String requestId, String runId) {
        requireAddress(node);
        relativeDataPath(absolutePath);
        String accessToken = issueScopedToken(node, absolutePath, "VERIFY", datasetId,
                datasetVersion, effectiveRequestId(requestId), runId);
        HttpURLConnection connection = null;
        try {
            String body = "{\"path\":\"" + jsonEscape(absolutePath) + "\""
                    + (expectedSize == null ? "" : ",\"expectedSize\":" + expectedSize)
                    + (expectedSha256 == null || expectedSha256.trim().isEmpty() ? ""
                    : ",\"expectedSha256\":\"" + jsonEscape(expectedSha256) + "\"") + "}";
            connection = open(new URL(baseUrl(node) + "/data-discovery/verify"), "POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            setBearer(connection, accessToken);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (DataOutputStream output = new DataOutputStream(connection.getOutputStream())) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throw uploadFailure("node file verification failed with HTTP " + status + detail(response));
            }
            return parseIntegrityResult(response, "node file verification");
        } catch (RegistrationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw uploadFailure("failed to verify dataset on node " + node.getNodeName()
                    + ": " + ex.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public void delete(NodeManagement node, String absolutePath) {
        delete(node, absolutePath, null, null, null, null);
    }

    public void delete(NodeManagement node, String absolutePath, Long datasetId,
                       String datasetVersion, String requestId, String runId) {
        requireAddress(node);
        relativeDataPath(absolutePath);
        String accessToken = issueScopedToken(node, absolutePath, "DELETE", datasetId,
                datasetVersion, effectiveRequestId(requestId), runId);
        HttpURLConnection connection = null;
        try {
            connection = open(new URL(baseUrl(node) + "/data-discovery/delete/"
                    + encodeAbsolutePath(absolutePath)), "DELETE");
            setBearer(connection, accessToken);
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throw uploadFailure("source dataset deletion failed with HTTP " + status + detail(response));
            }
        } catch (IOException ex) {
            throw uploadFailure("failed to delete source dataset: " + ex.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public void deleteQuietly(NodeManagement node, String absolutePath) {
        deleteQuietly(node, absolutePath, null, null, null, null);
    }

    public void deleteQuietly(NodeManagement node, String absolutePath, Long datasetId,
                              String datasetVersion, String requestId, String runId) {
        if (node == null || node.getInternalIp() == null || absolutePath == null) return;
        HttpURLConnection connection = null;
        try {
            relativeDataPath(absolutePath);
            String accessToken = issueScopedToken(node, absolutePath, "DELETE", datasetId,
                    datasetVersion, effectiveRequestId(requestId), runId);
            connection = open(new URL(baseUrl(node) + "/data-discovery/delete/"
                    + encodeAbsolutePath(absolutePath)), "DELETE");
            setBearer(connection, accessToken);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                log.warn("Failed to clean uploaded dataset {} from node {}: HTTP {}",
                        absolutePath, node.getNodeName(), status);
            }
        } catch (Exception ex) {
            log.warn("Failed to clean uploaded dataset {} from node {}: {}",
                    absolutePath, node.getNodeName(), ex.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String relativeDataPath(String absolutePath) {
        Path root = Paths.get(dataDirectory).toAbsolutePath().normalize();
        Path path = Paths.get(absolutePath).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw RegistrationException.invalid("dataset path is outside data directory");
        }
        return root.relativize(path).toString().replace('\\', '/');
    }

    private String absoluteDataPath(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw RegistrationException.invalid("dataset path is required");
        }
        Path root = Paths.get(dataDirectory).toAbsolutePath().normalize();
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw RegistrationException.invalid("dataset path is outside data directory");
        }
        return path.toString();
    }

    private String issueScopedToken(NodeManagement node, String absolutePath, String action,
                                    Long datasetId, String datasetVersion,
                                    String requestId, String runId) {
        if (accessAuthorization == null) {
            throw uploadFailure("dataset access authorization service is unavailable");
        }
        requireAddress(node);
        String effectiveDatasetId = datasetId == null ? INTERNAL_DATASET_ID : String.valueOf(datasetId);
        String effectiveVersion = datasetVersion == null || datasetVersion.trim().isEmpty()
                ? UNVERSIONED : datasetVersion.trim();
        AccessAuthorizationResult authorization = accessAuthorization.issueInternal(
                new AccessScope(effectiveDatasetId, effectiveVersion, absolutePath,
                        action, node.getNodeName()),
                new AccessAuditContext(requestId, runId, null));
        String token = authorization == null ? null : authorization.getToken();
        if (token == null || token.trim().isEmpty()) {
            throw uploadFailure("dataset access authorization returned an empty " + action + " token");
        }
        return token.trim();
    }

    private String effectiveRequestId(String requestId) {
        return requestId == null || requestId.trim().isEmpty()
                ? UUID.randomUUID().toString() : requestId.trim();
    }

    private void setBearer(HttpURLConnection connection, String token) {
        connection.setRequestProperty("Authorization", "Bearer " + token);
    }

    private String encodeAbsolutePath(String absolutePath) throws IOException {
        StringBuilder encodedPath = new StringBuilder();
        for (String segment : absolutePath.split("/")) {
            if (segment.isEmpty()) continue;
            if (encodedPath.length() > 0) encodedPath.append('/');
            encodedPath.append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20"));
        }
        return encodedPath.toString();
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private FileIntegrityResult parseIntegrityResult(String response, String operation) {
        try {
            FileIntegrityResult result = JSON.readValue(response, FileIntegrityResult.class);
            if (result == null || result.getDigest() == null
                    || !"SHA-256".equalsIgnoreCase(result.getAlgorithm())
                    || normalizeSha256(result.getDigest()) == null) {
                throw uploadFailure(operation + " returned no valid SHA-256 measurement");
            }
            return result;
        } catch (RegistrationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw uploadFailure(operation + " returned invalid JSON: " + ex.getMessage());
        }
    }

    private void requireSha256(String value) {
        if (normalizeSha256(value) == null) {
            throw RegistrationException.invalid("a non-empty SHA-256 digest is required");
        }
    }

    private String normalizeSha256(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("sha256:")) normalized = normalized.substring("sha256:".length());
        return normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private HttpURLConnection open(URL url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private void writeField(DataOutputStream output, String boundary,
                            String name, String value) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String readResponse(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && result.length() < 4096) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private String baseUrl(NodeManagement node) {
        return "http://" + node.getInternalIp() + ":" + discoveryPort;
    }

    private void requireAddress(NodeManagement node) {
        if (node == null || node.getInternalIp() == null || node.getInternalIp().trim().isEmpty()) {
            throw RegistrationException.invalid("UPLOAD_NODE_ADDRESS_MISSING",
                    "target node internal IP is missing");
        }
    }

    private RegistrationException uploadFailure(String message) {
        return new RegistrationException(HttpStatus.BAD_GATEWAY, "DATASET_UPLOAD_FAILED", message);
    }

    private String detail(String response) {
        return response == null || response.trim().isEmpty() ? "" : ": " + response.trim();
    }
}
