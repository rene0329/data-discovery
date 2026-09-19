package org.example.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A byte-bounded LRU of verified file blocks. Entries are usable only after a complete read. */
@Service
public class NodeReadCacheService {
    private final long maxBytes;
    private final int blockBytes;
    private long usedBytes;
    private final LinkedHashMap<BlockKey, byte[]> blocks = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Manifest> manifests = new java.util.HashMap<>();

    public NodeReadCacheService(
            @Value("${file.read-cache.max-bytes:134217728}") long maxBytes,
            @Value("${file.read-cache.block-bytes:1048576}") int blockBytes) {
        this.maxBytes = Math.max(0, maxBytes);
        this.blockBytes = Math.max(64 * 1024, blockBytes);
    }

    public int blockBytes() { return blockBytes; }

    public synchronized List<byte[]> completeBlocks(String cacheKey, long expectedSize, String expectedChecksum) {
        Manifest manifest = manifests.get(cacheKey);
        if (manifest == null || manifest.size != expectedSize || !same(manifest.checksum, expectedChecksum)) return null;
        List<byte[]> result = new ArrayList<>(manifest.blockCount);
        for (int i = 0; i < manifest.blockCount; i++) {
            byte[] bytes = blocks.get(new BlockKey(cacheKey, i));
            if (bytes == null) {
                manifests.remove(cacheKey);
                return null;
            }
            result.add(bytes);
        }
        return result;
    }

    public synchronized void putBlock(String cacheKey, int index, byte[] bytes) {
        if (maxBytes == 0 || bytes == null || bytes.length == 0 || bytes.length > maxBytes) return;
        BlockKey key = new BlockKey(cacheKey, index);
        byte[] previous = blocks.remove(key);
        if (previous != null) usedBytes -= previous.length;
        blocks.put(key, bytes);
        usedBytes += bytes.length;
        evict();
    }

    public synchronized boolean markComplete(String cacheKey, int blockCount, long size, String checksum) {
        for (int i = 0; i < blockCount; i++) if (!blocks.containsKey(new BlockKey(cacheKey, i))) return false;
        manifests.put(cacheKey, new Manifest(blockCount, size, checksum));
        return true;
    }

    public synchronized void invalidate(String cacheKey) {
        manifests.remove(cacheKey);
        java.util.Iterator<Map.Entry<BlockKey, byte[]>> iterator = blocks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, byte[]> entry = iterator.next();
            if (entry.getKey().cacheKey.equals(cacheKey)) {
                usedBytes -= entry.getValue().length;
                iterator.remove();
            }
        }
    }

    public synchronized void clear() {
        manifests.clear();
        blocks.clear();
        usedBytes = 0;
    }

    private void evict() {
        java.util.Iterator<Map.Entry<BlockKey, byte[]>> iterator = blocks.entrySet().iterator();
        while (usedBytes > maxBytes && iterator.hasNext()) {
            Map.Entry<BlockKey, byte[]> entry = iterator.next();
            usedBytes -= entry.getValue().length;
            manifests.remove(entry.getKey().cacheKey);
            iterator.remove();
        }
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private static final class Manifest {
        private final int blockCount;
        private final long size;
        private final String checksum;
        private Manifest(int blockCount, long size, String checksum) {
            this.blockCount = blockCount; this.size = size; this.checksum = checksum;
        }
    }

    private static final class BlockKey {
        private final String cacheKey;
        private final int index;
        private BlockKey(String cacheKey, int index) { this.cacheKey = cacheKey; this.index = index; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BlockKey)) return false;
            BlockKey that = (BlockKey) other;
            return index == that.index && cacheKey.equals(that.cacheKey);
        }
        @Override public int hashCode() { return 31 * cacheKey.hashCode() + index; }
    }
}
