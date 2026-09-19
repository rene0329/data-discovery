package org.example.controller;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.example.security.access.DatasetAccessScopeVerifier;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class DataDiscoveryControllerTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void copyFromStreamsToTemporaryFileAndAtomicallyPublishesTarget() throws Exception {
        byte[] payload = "streamed-dataset".getBytes(StandardCharsets.UTF_8);
        String sourceUrl = startSourceServer(payload);
        DataDiscoveryController controller = controller();

        Map<String, Object> request = new HashMap<>();
        request.put("sourceUrl", sourceUrl);
        request.put("path", "nested/data.npz");
        request.put("expectedSize", payload.length);
        request.put("expectedSha256", sha256(payload));
        request.put("sourceToken", "source-read-token");

        ResponseEntity<Map<String, Object>> response = controller.copyFrom(request, authorizedRequest());

        assertEquals(200, response.getStatusCodeValue());
        assertArrayEquals(payload, Files.readAllBytes(tempDir.resolve("nested/data.npz")));
    }

    @Test
    void copyFromRejectsSizeMismatchWithoutPublishingPartialFile() throws Exception {
        String sourceUrl = startSourceServer("short".getBytes(StandardCharsets.UTF_8));
        DataDiscoveryController controller = controller();

        Map<String, Object> request = new HashMap<>();
        request.put("sourceUrl", sourceUrl);
        request.put("path", "data.npz");
        request.put("expectedSize", 999L);
        request.put("expectedSha256", sha256("short".getBytes(StandardCharsets.UTF_8)));
        request.put("sourceToken", "source-read-token");

        ResponseEntity<Map<String, Object>> response = controller.copyFrom(request, authorizedRequest());

        assertEquals(502, response.getStatusCodeValue());
        assertFalse(Files.exists(tempDir.resolve("data.npz")));
    }

    @Test
    void copyFromRejectsPathTraversal() {
        DataDiscoveryController controller = controller();
        Map<String, Object> request = new HashMap<>();
        request.put("sourceUrl", "http://127.0.0.1:8080/data-discovery/download/data.npz");
        request.put("path", "../outside.npz");
        request.put("expectedSize", 1L);
        request.put("expectedSha256", sha256(new byte[] {1}));
        request.put("sourceToken", "source-read-token");

        ResponseEntity<Map<String, Object>> response = controller.copyFrom(request, authorizedRequest());

        assertEquals(400, response.getStatusCodeValue());
    }

    @Test
    void uploadPublishesFileAtomicallyWithoutOverwrite() throws Exception {
        DataDiscoveryController controller = controller();
        byte[] payload = "uploaded-dataset".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "dataset.npz", "application/octet-stream", payload);

        ResponseEntity<Map<String, Object>> response =
                controller.uploadFile(file, "uploads/demo/1.0/demo.npz", false, authorizedRequest());

        assertEquals(200, response.getStatusCodeValue());
        assertArrayEquals(payload, Files.readAllBytes(
                tempDir.resolve("uploads/demo/1.0/demo.npz")));
        try (java.util.stream.Stream<Path> paths = Files.walk(tempDir)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().contains(".part-")));
        }
    }

    @Test
    void uploadRejectsExistingTargetWhenOverwriteIsDisabled() throws Exception {
        DataDiscoveryController controller = controller();
        Path target = tempDir.resolve("uploads/demo/1.0/demo.npz");
        Files.createDirectories(target.getParent());
        Files.write(target, "original".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file = new MockMultipartFile(
                "file", "dataset.npz", "application/octet-stream",
                "replacement".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Map<String, Object>> response =
                controller.uploadFile(file, "uploads/demo/1.0/demo.npz", false, authorizedRequest());

        assertEquals(409, response.getStatusCodeValue());
        assertEquals("original", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    @Test
    void copyFromRejectsSameLengthDigestMismatchAndPreservesExistingTarget() throws Exception {
        byte[] payload = "changed!".getBytes(StandardCharsets.UTF_8);
        byte[] expected = "original".getBytes(StandardCharsets.UTF_8);
        String sourceUrl = startSourceServer(payload);
        Path target = tempDir.resolve("data.bin");
        Files.write(target, expected);
        DataDiscoveryController controller = controller();
        Map<String, Object> request = new HashMap<>();
        request.put("sourceUrl", sourceUrl);
        request.put("path", "data.bin");
        request.put("expectedSize", payload.length);
        request.put("expectedSha256", sha256(expected));
        request.put("sourceToken", "source-read-token");

        ResponseEntity<Map<String, Object>> response = controller.copyFrom(request, authorizedRequest());

        assertEquals(502, response.getStatusCodeValue());
        assertArrayEquals(expected, Files.readAllBytes(target));
    }

    @Test
    void verifyNeverTreatsMissingExpectedDigestAsSuccess() throws Exception {
        byte[] payload = "dataset".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("dataset.csv"), payload);
        DataDiscoveryController controller = controller();
        Map<String, Object> request = new HashMap<>();
        request.put("path", "dataset.csv");
        request.put("expectedSize", payload.length);

        ResponseEntity<org.example.model.FileIntegrityResult> response =
                controller.verifyFile(request, authorizedRequest());

        assertEquals(200, response.getStatusCodeValue());
        assertFalse(response.getBody().isVerified());
        assertEquals(sha256(payload), response.getBody().getDigest());
    }

    @Test
    void strictModeRejectsMissingTokensOnVerifyWriteCopyAndDelete() throws Exception {
        byte[] payload = "dataset".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("dataset.bin"), payload);
        DataDiscoveryController controller = controller();
        MockHttpServletRequest missing = new MockHttpServletRequest();

        Map<String, Object> verifyRequest = new HashMap<>();
        verifyRequest.put("path", "dataset.bin");
        verifyRequest.put("expectedSize", payload.length);
        assertEquals(401, controller.verifyFile(verifyRequest, missing).getStatusCodeValue());

        MockMultipartFile upload = new MockMultipartFile(
                "file", "new.bin", "application/octet-stream", payload);
        assertEquals(401, controller.uploadFile(upload, "new.bin", false, missing)
                .getStatusCodeValue());

        Map<String, Object> copyRequest = new HashMap<>();
        copyRequest.put("sourceUrl", "http://127.0.0.1/source");
        copyRequest.put("sourceToken", "source-read-token");
        copyRequest.put("path", "copy.bin");
        copyRequest.put("expectedSize", payload.length);
        copyRequest.put("expectedSha256", sha256(payload));
        assertEquals(401, controller.copyFrom(copyRequest, missing).getStatusCodeValue());

        missing.setRequestURI("/data-discovery/delete/"
                + tempDir.resolve("dataset.bin").toString().substring(1));
        assertEquals(401, controller.deleteFile(missing).getStatusCodeValue());
        assertArrayEquals(payload, Files.readAllBytes(tempDir.resolve("dataset.bin")));
    }

    private DataDiscoveryController controller() {
        DataDiscoveryController controller = new DataDiscoveryController();
        ReflectionTestUtils.setField(controller, "dataDirectory", tempDir.toString());
        ReflectionTestUtils.setField(controller, "transferConnectTimeoutMs", 1000);
        ReflectionTestUtils.setField(controller, "transferReadTimeoutMs", 1000);
        ReflectionTestUtils.setField(controller, "fileDiscoveryService", new org.example.service.FileDiscoveryService());
        DatasetAccessScopeVerifier verifier = mock(DatasetAccessScopeVerifier.class);
        doThrow(new DatasetAccessScopeVerifier.TokenVerificationException(
                "TOKEN_MISSING", "token required"))
                .when(verifier).verifyPathAction(isNull(), anyString(), anyString());
        ReflectionTestUtils.setField(controller, "accessTokens", verifier);
        ReflectionTestUtils.setField(controller, "strictDownloadTokens", true);
        return controller;
    }

    private MockHttpServletRequest authorizedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test-token");
        return request;
    }

    private String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String startSourceServer(byte[] payload) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/source", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/source";
    }
}
