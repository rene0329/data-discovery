package org.example.controller;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

        ResponseEntity<Map<String, Object>> response = controller.copyFrom(request);

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

        ResponseEntity<Map<String, Object>> response = controller.copyFrom(request);

        assertEquals(502, response.getStatusCodeValue());
        assertFalse(Files.exists(tempDir.resolve("data.npz")));
    }

    @Test
    void copyFromRejectsPathTraversal() {
        DataDiscoveryController controller = controller();
        Map<String, Object> request = new HashMap<>();
        request.put("sourceUrl", "http://127.0.0.1:8080/data-discovery/download/data.npz");
        request.put("path", "../outside.npz");

        ResponseEntity<Map<String, Object>> response = controller.copyFrom(request);

        assertEquals(400, response.getStatusCodeValue());
    }

    private DataDiscoveryController controller() {
        DataDiscoveryController controller = new DataDiscoveryController();
        ReflectionTestUtils.setField(controller, "dataDirectory", tempDir.toString());
        ReflectionTestUtils.setField(controller, "transferConnectTimeoutMs", 1000);
        ReflectionTestUtils.setField(controller, "transferReadTimeoutMs", 1000);
        return controller;
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
