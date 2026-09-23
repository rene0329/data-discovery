package org.example.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * Result of streaming a file through a node Agent integrity verifier.
 * The digest is always measured from the physical file. The verified flag is
 * true only when a non-empty expected SHA-256 and byte count both match.
 * Agent responses also carry legacy fields such as {@code status} and
 * {@code size}; they are ignored rather than failing an otherwise good copy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileIntegrityResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String path;
    private long sizeBytes;
    private String algorithm;
    private String digest;
    private boolean verified;
    private String message;

    public FileIntegrityResult() {
    }

    public FileIntegrityResult(String path, long sizeBytes, String algorithm,
                               String digest, boolean verified, String message) {
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.algorithm = algorithm;
        this.digest = digest;
        this.verified = verified;
        this.message = message;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
    public String getDigest() { return digest; }
    public void setDigest(String digest) { this.digest = digest; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
