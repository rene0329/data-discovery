package org.example.service;

import java.io.IOException;
import java.nio.file.Path;

/** Calculates the standard SHA-256 digest of one complete file. */
@FunctionalInterface
public interface Sha256FileDigestCalculator {
    String calculate(Path file) throws IOException;
}
