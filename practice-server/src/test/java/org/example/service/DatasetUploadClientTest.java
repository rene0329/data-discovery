package org.example.service;

import com.sun.net.httpserver.HttpServer;
import org.example.entity.NodeManagement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.example.security.access.AccessAuthorizationResult;
import org.example.security.access.AccessScope;
import org.example.security.access.DatasetAccessAuthorizationService;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetUploadClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void uploadStreamsExpectedMultipartRequestToNodeAgent() throws Exception {
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        DatasetAccessAuthorizationService authorization = authorization();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data-discovery/upload", exchange -> {
            requestBody.set(readAll(exchange.getRequestBody()));
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DatasetUploadClient client = new DatasetUploadClient(
                server.getAddress().getPort(), 1000, 5000, "/dataset", authorization);
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
        assertEquals("Bearer token-WRITE", authorizationHeader.get());
    }

    @Test
    void copyFromSendsSourceUrlAndRelativeTargetPath() throws Exception {
        String digest = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        AtomicReference<String> requestBody = new AtomicReference<>();
        DatasetAccessAuthorizationService authorization = authorization();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data-discovery/copy-from", exchange -> {
            requestBody.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"path\":\"mnist/mnist-1.0.npz\",\"sizeBytes\":123,"
                    + "\"algorithm\":\"SHA-256\",\"digest\":\"" + digest
                    + "\",\"verified\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        DatasetUploadClient client = new DatasetUploadClient(
                server.getAddress().getPort(), 1000, 5000, "/dataset", authorization);
        NodeManagement source = NodeManagement.builder().nodeName("source")
                .internalIp("127.0.0.1").build();
        NodeManagement target = NodeManagement.builder().nodeName("target")
                .internalIp("127.0.0.1").build();

        client.copyFrom(source, target, "/dataset/mnist/mnist-1.0.npz", 123L, digest,
                42L, "1.0", "copy-request-7", "run-11");

        assertTrue(requestBody.get().contains("/data-discovery/download/dataset/mnist/mnist-1.0.npz"));
        assertTrue(requestBody.get().contains("\"path\":\"mnist/mnist-1.0.npz\""));
        assertTrue(requestBody.get().contains("\"expectedSize\":123"));
        assertTrue(requestBody.get().contains("\"expectedSha256\":\"" + digest + "\""));
        assertTrue(requestBody.get().contains("\"sourceToken\":\"token-READ\""));
        assertEquals("Bearer token-COPY", authorizationHeader.get());
        verify(authorization).issueInternal(argThat(scope ->
                        "42".equals(scope.getDatasetId())
                                && "1.0".equals(scope.getDatasetVersion())
                                && "/dataset/mnist/mnist-1.0.npz".equals(scope.getPath())
                                && "READ".equals(scope.getAction())
                                && "source".equals(scope.getTargetNode())),
                argThat(context -> "copy-request-7".equals(context.getRequestId())
                        && "run-11".equals(context.getRunId())));
        verify(authorization).issueInternal(argThat(scope ->
                        "42".equals(scope.getDatasetId())
                                && "1.0".equals(scope.getDatasetVersion())
                                && "/dataset/mnist/mnist-1.0.npz".equals(scope.getPath())
                                && "COPY".equals(scope.getAction())
                                && "target".equals(scope.getTargetNode())),
                argThat(context -> "copy-request-7".equals(context.getRequestId())
                        && "run-11".equals(context.getRunId())));
    }

    @Test
    void verifyReturnsMeasurementButDoesNotInventSuccessWithoutExpectedDigest() throws Exception {
        String digest = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        DatasetAccessAuthorizationService authorization = authorization();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data-discovery/verify", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"path\":\"/dataset/data.csv\",\"sizeBytes\":7,"
                    + "\"algorithm\":\"SHA-256\",\"digest\":\"" + digest
                    + "\",\"verified\":false,\"message\":\"expected SHA-256 is required\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        DatasetUploadClient client = new DatasetUploadClient(
                server.getAddress().getPort(), 1000, 5000, "/dataset", authorization);
        NodeManagement node = NodeManagement.builder().nodeName("storage")
                .internalIp("127.0.0.1").build();

        org.example.model.FileIntegrityResult result =
                client.verify(node, "/dataset/data.csv", 7L, null);

        assertEquals(digest, result.getDigest());
        assertEquals(false, result.isVerified());
        assertEquals("Bearer token-VERIFY", authorizationHeader.get());
    }

    @Test
    void strictDeleteSurfacesAgentFailures() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        DatasetAccessAuthorizationService authorization = authorization();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data-discovery/delete/dataset/test.npz", exchange -> {
            method.set(exchange.getRequestMethod());
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        DatasetUploadClient client = new DatasetUploadClient(
                server.getAddress().getPort(), 1000, 5000, "/dataset", authorization);
        NodeManagement node = NodeManagement.builder().nodeName("source").internalIp("127.0.0.1").build();
        assertThrows(RegistrationException.class, () -> client.delete(node, "/dataset/test.npz"));
        assertEquals("DELETE", method.get());
        assertEquals("Bearer token-DELETE", authorizationHeader.get());
    }

    private DatasetAccessAuthorizationService authorization() {
        DatasetAccessAuthorizationService authorization = mock(DatasetAccessAuthorizationService.class);
        when(authorization.issueInternal(any(), any())).thenAnswer(invocation -> {
            AccessScope scope = invocation.getArgument(0);
            AccessAuthorizationResult result = new AccessAuthorizationResult();
            result.setToken("token-" + scope.getAction());
            return result;
        });
        return authorization;
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }
}
