package org.example.service;

import com.sun.net.httpserver.HttpServer;
import org.example.entity.NodeManagement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.example.exception.RegistrationException;

class DatasetUploadClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void uploadStreamsExpectedMultipartRequestToNodeAgent() throws Exception {
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data-discovery/upload", exchange -> {
            requestBody.set(readAll(exchange.getRequestBody()));
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DatasetUploadClient client = new DatasetUploadClient(
                server.getAddress().getPort(), 1000, 5000, "/dataset");
        NodeManagement node = NodeManagement.builder().nodeName("storage-1")
                .internalIp("127.0.0.1").build();
        MockMultipartFile file = new MockMultipartFile("file", "sales.npz",
                "application/octet-stream", "npz-payload".getBytes(StandardCharsets.UTF_8));

        client.upload(node, file, "uploads/sales/1.0/sales-1.0.npz");

        String body = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"path\""));
        assertTrue(body.contains("uploads/sales/1.0/sales-1.0.npz"));
        assertTrue(body.contains("name=\"overwrite\""));
        assertTrue(body.contains("\r\nfalse\r\n"));
        assertTrue(body.contains("name=\"file\"; filename=\"dataset.npz\""));
        assertTrue(body.contains("npz-payload"));
    }

    @Test
    void copyFromSendsSourceUrlAndRelativeTargetPath() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data-discovery/copy-from", exchange -> {
            requestBody.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        DatasetUploadClient client = new DatasetUploadClient(
                server.getAddress().getPort(), 1000, 5000, "/dataset");
        NodeManagement source = NodeManagement.builder().nodeName("source")
                .internalIp("127.0.0.1").build();
        NodeManagement target = NodeManagement.builder().nodeName("target")
                .internalIp("127.0.0.1").build();

        client.copyFrom(source, target, "/dataset/mnist/mnist-1.0.npz", 123L);

        assertTrue(requestBody.get().contains("/data-discovery/download/dataset/mnist/mnist-1.0.npz"));
        assertTrue(requestBody.get().contains("\"path\":\"mnist/mnist-1.0.npz\""));
        assertTrue(requestBody.get().contains("\"expectedSize\":123"));
    }

    @Test
    void strictDeleteSurfacesAgentFailures() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data-discovery/delete/dataset/test.npz", exchange -> {
            method.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        DatasetUploadClient client = new DatasetUploadClient(server.getAddress().getPort(), 1000, 5000, "/dataset");
        NodeManagement node = NodeManagement.builder().nodeName("source").internalIp("127.0.0.1").build();
        assertThrows(RegistrationException.class, () -> client.delete(node, "/dataset/test.npz"));
        assertEquals("DELETE", method.get());
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }
}
