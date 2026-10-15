package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses the operating system's OpenSSL executable so supported CPUs can use the
 * native SHA extensions even when the service still runs on an older JVM.
 */
@Component
public class OpenSslSha256FileDigestCalculator implements Sha256FileDigestCalculator {
    private static final Logger log = LoggerFactory.getLogger(OpenSslSha256FileDigestCalculator.class);
    private static final Pattern SHA256_OUTPUT =
            Pattern.compile("(?m)^([0-9a-fA-F]{64})(?:\\s|$)");
    private static final int MAX_CAPTURED_OUTPUT_BYTES = 16 * 1024;

    private final Path executable;
    private final long timeoutSeconds;
    private final Sha256FileDigestCalculator fallback;

    @Autowired
    public OpenSslSha256FileDigestCalculator(
            @Value("${file.verification.openssl.path:/usr/bin/openssl}") String executable,
            @Value("${file.verification.openssl.timeout-seconds:2400}") long timeoutSeconds) {
        this(Paths.get(executable), timeoutSeconds, new JavaSha256FileDigestCalculator());
    }

    OpenSslSha256FileDigestCalculator(Path executable, long timeoutSeconds,
                                      Sha256FileDigestCalculator fallback) {
        this.executable = executable.toAbsolutePath().normalize();
        this.timeoutSeconds = Math.max(1L, timeoutSeconds);
        this.fallback = fallback;
    }

    @Override
    public String calculate(Path file) throws IOException {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            log.warn("OpenSSL SHA-256 executable is unavailable at {}; using the Java fallback", executable);
            return fallback.calculate(file);
        }

        Process process;
        try {
            process = new ProcessBuilder(Arrays.asList(
                    executable.toString(), "dgst", "-sha256", "-r", "--", file.toString()))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException unavailable) {
            log.warn("Cannot start OpenSSL SHA-256 at {}; using the Java fallback: {}",
                    executable, unavailable.getMessage());
            return fallback.calculate(file);
        }

        ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "openssl-sha256-output");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> output = reader.submit(() -> readOutput(process.getInputStream()));
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("OpenSSL SHA-256 timed out after " + timeoutSeconds + " seconds");
            }
            String text = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IOException("OpenSSL SHA-256 exited with code " + process.exitValue()
                        + outputDetail(text));
            }
            Matcher matcher = SHA256_OUTPUT.matcher(text);
            if (!matcher.find()) {
                throw new IOException("OpenSSL SHA-256 returned an invalid digest" + outputDetail(text));
            }
            return matcher.group(1).toLowerCase(Locale.ROOT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            InterruptedIOException error = new InterruptedIOException("OpenSSL SHA-256 was interrupted");
            error.initCause(interrupted);
            throw error;
        } catch (ExecutionException | TimeoutException outputFailure) {
            process.destroyForcibly();
            throw new IOException("Failed to collect OpenSSL SHA-256 output", outputFailure);
        } finally {
            reader.shutdownNow();
        }
    }

    private String readOutput(InputStream input) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = MAX_CAPTURED_OUTPUT_BYTES - captured.size();
            if (remaining > 0) captured.write(buffer, 0, Math.min(read, remaining));
        }
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    private String outputDetail(String output) {
        if (output == null || output.trim().isEmpty()) return "";
        String compact = output.trim().replaceAll("\\s+", " ");
        return ": " + compact.substring(0, Math.min(compact.length(), 512));
    }
}
