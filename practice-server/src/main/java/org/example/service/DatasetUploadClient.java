package org.example.service;

import org.example.entity.NodeManagement;
import org.example.exception.RegistrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final int discoveryPort;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String dataDirectory;

    public DatasetUploadClient(
            @Value("${dispatch.data-discovery.port:8080}") int discoveryPort,
            @Value("${app.storage-transfer.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.storage-transfer.read-timeout-ms:600000}") int readTimeoutMs,
            @Value("${dispatch.data-discovery.data-directory:/dataset}") String dataDirectory) {
        this.discoveryPort = discoveryPort;
        this.connectTimeoutMs = Math.max(1000, connectTimeoutMs);
        this.readTimeoutMs = Math.max(1000, readTimeoutMs);
        this.dataDirectory = Paths.get(dataDirectory).toAbsolutePath().normalize().toString();
    }

    public void upload(NodeManagement node, MultipartFile file, String relativePath) {
        requireAddress(node);
        HttpURLConnection connection = null;
        String boundary = "----topic4-" + UUID.randomUUID();
        try {
            URL url = new URL(baseUrl(node) + "/data-discovery/upload");
            connection = open(url, "POST");
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(BUFFER_SIZE);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

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

    public void copyFrom(NodeManagement source, NodeManagement target,
                         String absolutePath, Long expectedSize) {
        requireAddress(source);
        requireAddress(target);
        HttpURLConnection connection = null;
        try {
            String sourceUrl = baseUrl(source) + "/data-discovery/download/"
                    + encodeAbsolutePath(absolutePath);
            String relativePath = relativeDataPath(absolutePath);
            String body = "{\"sourceUrl\":\"" + jsonEscape(sourceUrl)
                    + "\",\"path\":\"" + jsonEscape(relativePath) + "\""
                    + (expectedSize == null ? "" : ",\"expectedSize\":" + expectedSize) + "}";
            connection = open(new URL(baseUrl(target) + "/data-discovery/copy-from"), "POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
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
        } catch (RegistrationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw uploadFailure("failed to copy dataset from " + source.getNodeName()
                    + " to " + target.getNodeName() + ": " + ex.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public void deleteQuietly(NodeManagement node, String absolutePath) {
        if (node == null || node.getInternalIp() == null || absolutePath == null) return;
        HttpURLConnection connection = null;
        try {
            StringBuilder encodedPath = new StringBuilder();
            for (String segment : absolutePath.split("/")) {
                if (segment.isEmpty()) continue;
                if (encodedPath.length() > 0) encodedPath.append('/');
                encodedPath.append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20"));
            }
            connection = open(new URL(baseUrl(node) + "/data-discovery/delete/" + encodedPath), "DELETE");
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
