package org.example.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenSslSha256FileDigestCalculatorTest {
    private static final String SYSTEM_DIGEST =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir
    Path directory;

    @Test
    void usesSystemToolWithoutPassingThePathThroughAShell() throws Exception {
        Path executable = executable("fake openssl.sh",
                "#!/bin/sh\nprintf '%s  *%s\\n' '" + SYSTEM_DIGEST + "' \"$5\"\n");
        Path file = directory.resolve("data file; echo unsafe.csv");
        Files.write(file, "content".getBytes(StandardCharsets.UTF_8));
        Sha256FileDigestCalculator fallback = ignored -> {
            throw new AssertionError("Java fallback must not be used");
        };

        String digest = new OpenSslSha256FileDigestCalculator(executable, 5, fallback)
                .calculate(file);

        assertEquals(SYSTEM_DIGEST, digest);
    }

    @Test
    void fallsBackToJavaOnlyWhenTheSystemToolIsUnavailable() throws Exception {
        Path file = directory.resolve("fallback.bin");
        Files.write(file, "fallback".getBytes(StandardCharsets.UTF_8));

        String digest = new OpenSslSha256FileDigestCalculator(
                directory.resolve("missing-openssl"), 5, new JavaSha256FileDigestCalculator())
                .calculate(file);

        assertEquals(javaSha256(file), digest);
    }

    @Test
    void rejectsInvalidOutputFromAStartedSystemToolInsteadOfSilentlyFallingBack() throws Exception {
        Path executable = executable("invalid-openssl.sh", "#!/bin/sh\necho not-a-digest\n");
        Path file = directory.resolve("data.bin");
        Files.write(file, new byte[] {1, 2, 3});
        Sha256FileDigestCalculator fallback = ignored -> SYSTEM_DIGEST;

        assertThrows(java.io.IOException.class,
                () -> new OpenSslSha256FileDigestCalculator(executable, 5, fallback).calculate(file));
    }

    private Path executable(String name, String script) throws Exception {
        Path path = directory.resolve(name);
        Files.write(path, script.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return path;
    }

    private String javaSha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }
}
