package org.example.service;

import org.example.model.NodeReadRequest;
import org.example.model.NodeReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class NodeDatasetReadServiceTest {
    @TempDir Path directory;

    @Test
    void readsVerifiedLocalFileThenServesCompleteMemoryHit() throws Exception {
        byte[] content = "verified dataset content".getBytes(StandardCharsets.UTF_8);
        Path file = directory.resolve("dataset.bin");
        Files.write(file, content);
        NodeReadCacheService cache = new NodeReadCacheService(1024 * 1024, 64 * 1024);
        NodeDatasetReadService service = new NodeDatasetReadService(cache, directory.toString(), 1000, 1000);
        NodeReadRequest request = request(file, content);

        NodeReadResult first = service.read(request);
        NodeReadResult second = service.read(request);

        assertEquals("LOCAL_STORAGE", first.getLayer());
        assertEquals("MEMORY", second.getLayer());
        assertEquals(content.length, second.getBytesRead());
        assertEquals(content.length, second.getCacheHitBytes());
        assertTrue(second.isCacheComplete());
    }

    @Test
    void rejectsChecksumMismatchAndDoesNotExposePartialCache() throws Exception {
        byte[] content = "changed bytes".getBytes(StandardCharsets.UTF_8);
        Path file = directory.resolve("dataset.bin");
        Files.write(file, content);
        NodeReadCacheService cache = new NodeReadCacheService(1024 * 1024, 64 * 1024);
        NodeDatasetReadService service = new NodeDatasetReadService(cache, directory.toString(), 1000, 1000);
        NodeReadRequest request = request(file, content);
        request.setExpectedChecksum(repeat("00", 32));

        assertThrows(IOException.class, () -> service.read(request));
        request.setExpectedChecksum(sha256(content));
        assertEquals("LOCAL_STORAGE", service.read(request).getLayer());
    }

    private NodeReadRequest request(Path file, byte[] content) throws Exception {
        NodeReadRequest request = new NodeReadRequest();
        request.setRequestId("request-1");
        request.setCacheKey("dataset:1:" + sha256(content));
        request.setLocalPath(file.toString());
        request.setExpectedSize((long) content.length);
        request.setExpectedChecksum(sha256(content));
        return request;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            out.append(String.format("%02x", value & 0xff));
        }
        return out.toString();
    }

    private static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder();
        while (count-- > 0) out.append(text);
        return out.toString();
    }
}
