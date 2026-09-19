package org.example.security.aggregation;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Cryptographic primitives shared by the three secure-sum workers and the
 * coordinator HTTP client. Private keys and pairwise masks never leave a
 * worker process.
 */
public final class SecureAggregationProtocol {
    public static final String VERSION = "topic4-secure-sum-v1";
    public static final BigInteger MODULUS = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);

    private SecureAggregationProtocol() {
    }

    public static KeyPair generateEphemeralKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("unable to generate ephemeral ECDH key", ex);
        }
    }

    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid participant public key", ex);
        }
    }

    public static BigInteger derivePairwiseMask(PrivateKey ownPrivateKey,
                                                 PublicKey peerPublicKey,
                                                 String runId,
                                                 String firstParticipant,
                                                 String secondParticipant) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(ownPrivateKey);
            agreement.doPhase(peerPublicKey, true);
            byte[] sharedSecret = agreement.generateSecret();
            String lower = firstParticipant.compareTo(secondParticipant) < 0
                    ? firstParticipant : secondParticipant;
            String higher = firstParticipant.compareTo(secondParticipant) < 0
                    ? secondParticipant : firstParticipant;
            byte[] context = (VERSION + "\n" + runId + "\n" + lower + "\n" + higher)
                    .getBytes(StandardCharsets.UTF_8);
            byte[] derived = hmac(sharedSecret, context);
            return new BigInteger(1, derived).mod(MODULUS);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to derive pairwise mask", ex);
        }
    }

    public static String workerAuthTag(String secret, String phase, String runId,
                                       String participantId) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("worker authentication secret is required");
        }
        byte[] value = (VERSION + "\n" + phase + "\n" + runId + "\n" + participantId)
                .getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmac(secret.getBytes(StandardCharsets.UTF_8), value));
    }

    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }
}
