package org.example.service;

import org.example.model.NodeReadRequest;
import org.example.model.NodeReadResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

@Service
public class NodeDatasetReadService {
    private final NodeReadCacheService cache;
    private final Path dataRoot;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public NodeDatasetReadService(NodeReadCacheService cache,
            @Value("${file.discovery.data-directory:/data}") String dataDirectory,
            @Value("${file.transfer.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${file.transfer.read-timeout-ms:2700000}") int readTimeoutMs) {
        this.cache = cache;
        this.dataRoot = Paths.get(dataDirectory).toAbsolutePath().normalize();
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public NodeReadResult read(NodeReadRequest request) throws IOException {
        validate(request);
        if (request.isClearCache()) cache.invalidate(request.getCacheKey());
        long started = System.nanoTime();
        List<byte[]> cached = cache.completeBlocks(request.getCacheKey(), request.getExpectedSize(), request.getExpectedChecksum());
        if (cached != null) return readCached(request, cached, started);

        MessageDigest digest = sha256();
        long bytesRead = 0;
        long firstByteMs = -1;
        int blockIndex = 0;
        boolean local = request.getLocalPath() != null && !request.getLocalPath().trim().isEmpty();
        try (InputStream input = local ? Files.newInputStream(resolveLocal(request.getLocalPath())) : openRemote(request)) {
            byte[] buffer = new byte[cache.blockBytes()];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (firstByteMs < 0) firstByteMs = elapsedMs(started);
                digest.update(buffer, 0, read);
                cache.putBlock(request.getCacheKey(), blockIndex++, Arrays.copyOf(buffer, read));
                bytesRead += read;
            }
        } catch (IOException error) {
            cache.invalidate(request.getCacheKey());
            throw error;
        }
        String checksum = hex(digest.digest());
        requireExpected(request, bytesRead, checksum);
        boolean complete = cache.markComplete(request.getCacheKey(), blockIndex, bytesRead, checksum);
        return NodeReadResult.builder().requestId(request.getRequestId())
                .layer(local ? "LOCAL_STORAGE" : "REMOTE_STORAGE")
                .bytesRead(bytesRead).cacheHitBytes(0).firstByteMs(Math.max(0, firstByteMs))
                .durationMs(elapsedMs(started)).checksum(checksum).cacheComplete(complete).build();
    }

    public void clear(String cacheKey) {
        if (cacheKey == null || cacheKey.trim().isEmpty()) cache.clear(); else cache.invalidate(cacheKey.trim());
    }

    private NodeReadResult readCached(NodeReadRequest request, List<byte[]> blocks, long started) throws IOException {
        MessageDigest digest = sha256();
        long bytes = 0;
        long firstByteMs = 0;
        for (byte[] block : blocks) {
            if (bytes == 0) firstByteMs = elapsedMs(started);
            digest.update(block);
            bytes += block.length;
        }
        String checksum = hex(digest.digest());
        requireExpected(request, bytes, checksum);
        return NodeReadResult.builder().requestId(request.getRequestId()).layer("MEMORY")
                .bytesRead(bytes).cacheHitBytes(bytes).firstByteMs(firstByteMs)
                .durationMs(elapsedMs(started)).checksum(checksum).cacheComplete(true).build();
    }

    private InputStream openRemote(NodeReadRequest request) throws IOException {
        URI uri;
        try { uri = URI.create(request.getSourceUrl()); }
        catch (RuntimeException error) { throw new IOException("invalid source URL", error); }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("only internal HTTP source URLs are supported");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(request.getSourceUrl()).openConnection();
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("GET");
        if (request.getSourceToken() != null && !request.getSourceToken().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + request.getSourceToken());
        }
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("source returned HTTP " + status);
        }
        return new java.io.FilterInputStream(connection.getInputStream()) {
            @Override public void close() throws IOException { try { super.close(); } finally { connection.disconnect(); } }
        };
    }

    private Path resolveLocal(String path) throws IOException {
        Path resolved = Paths.get(path).toAbsolutePath().normalize();
        if (!resolved.startsWith(dataRoot)) throw new IOException("local path is outside data directory");
        if (!Files.isRegularFile(resolved)) throw new IOException("local source does not exist");
        return resolved;
    }

    private void validate(NodeReadRequest request) {
        if (request == null || blank(request.getRequestId()) || blank(request.getCacheKey())
                || request.getExpectedSize() == null || request.getExpectedSize() < 0
                || blank(request.getExpectedChecksum())
                || (blank(request.getLocalPath()) == blank(request.getSourceUrl()))) {
            throw new IllegalArgumentException("requestId, cacheKey, expectedSize, expectedChecksum and exactly one source are required");
        }
    }

    private void requireExpected(NodeReadRequest request, long bytes, String checksum) throws IOException {
        if (bytes != request.getExpectedSize()) {
            cache.invalidate(request.getCacheKey());
            throw new IOException("size mismatch: expected " + request.getExpectedSize() + " but read " + bytes);
        }
        if (!checksum.equalsIgnoreCase(request.getExpectedChecksum())) {
            cache.invalidate(request.getCacheKey());
            throw new IOException("SHA-256 mismatch");
        }
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000L; }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
}
