package org.example.service;

import org.example.model.FileData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileDiscoveryServiceTest {
    @TempDir
    Path directory;

    @Test
    void readsCompanionMetadataForNpzFile() throws Exception {
        Files.write(directory.resolve("mnist-1.0.npz"), new byte[] {1, 2, 3});
        String metadata = "{\"metadataVersion\":\"1.0\",\"dataset\":{\"datasetCode\":\"mnist\"}}";
        Files.write(directory.resolve("mnist-1.0.meta.json"),
                metadata.getBytes(StandardCharsets.UTF_8));
        FileDiscoveryService service = new FileDiscoveryService();
        ReflectionTestUtils.setField(service, "DATA_DIRECTORY", directory.toString());

        List<FileData> files = ReflectionTestUtils.invokeMethod(service, "scanRealFiles");

        assertEquals(1, files.size());
        assertEquals(metadata, files.get(0).getMetadataJson());
    }
}
