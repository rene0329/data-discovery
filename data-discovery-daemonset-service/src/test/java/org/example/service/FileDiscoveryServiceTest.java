package org.example.service;

import org.example.model.FileData;
import org.example.model.FileIntegrityResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void scansOrdinaryFilesAndDoesNotRegisterNpzSidecarSeparately() throws Exception {
        Files.write(directory.resolve("table.csv"), "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("images.npz"), new byte[] {1, 2, 3});
        Files.write(directory.resolve("images.meta.json"), "{}".getBytes(StandardCharsets.UTF_8));
        FileDiscoveryService service = new FileDiscoveryService();
        ReflectionTestUtils.setField(service, "DATA_DIRECTORY", directory.toString());

        List<FileData> files = ReflectionTestUtils.invokeMethod(service, "scanRealFiles");

        assertEquals(2, files.size());
        assertNotNull(files.stream().filter(file -> "CSV".equals(file.getFileType())).findFirst().orElse(null));
        assertFalse(files.stream().anyMatch(file -> file.getName().endsWith(".meta.json")));
    }

    @Test
    void verifiesEntireSparseFileLargerThanOneHundredMiBWithSha256() throws Exception {
        Path large = directory.resolve("large.bin");
        long size = 100L * 1024 * 1024 + 1;
        try (RandomAccessFile file = new RandomAccessFile(large.toFile(), "rw")) {
            file.setLength(size);
            file.seek(size - 1);
            file.write(7);
        }
        String expected = sha256(large);
        FileDiscoveryService service = new FileDiscoveryService();

        FileIntegrityResult result = service.verifyFile(large, size, expected);

        assertTrue(result.isVerified());
        assertEquals(size, result.getSizeBytes());
        assertEquals(expected, result.getDigest());
    }

    @Test
    void rejectsSameLengthSingleByteMutation() throws Exception {
        Path file = directory.resolve("mutable.bin");
        Files.write(file, "abcdef".getBytes(StandardCharsets.UTF_8));
        FileDiscoveryService service = new FileDiscoveryService();
        String authority = service.verifyFile(file, 6L, null).getDigest();
        try (RandomAccessFile changed = new RandomAccessFile(file.toFile(), "rw")) {
            changed.seek(3);
            changed.write('X');
        }

        FileIntegrityResult result = service.verifyFile(file, 6L, authority);

        assertFalse(result.isVerified());
        assertEquals("SHA-256 mismatch", result.getMessage());
    }

    @Test
    void emptyExpectedDigestNeverPassesVerification() throws Exception {
        Path file = directory.resolve("empty-authority.bin");
        Files.write(file, new byte[] {1, 2, 3});

        FileIntegrityResult result = new FileDiscoveryService().verifyFile(file, 3L, "  ");

        assertFalse(result.isVerified());
        assertNotNull(result.getDigest());
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte item : digest.digest()) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
