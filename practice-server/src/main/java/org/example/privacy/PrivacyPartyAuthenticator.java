package org.example.privacy;

import org.example.exception.RegistrationException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/** Authenticates the three logical parties and signs their approval evidence. */
@Component
public class PrivacyPartyAuthenticator {
    private final Map<Party, byte[]> secrets = new EnumMap<>(Party.class);

    public PrivacyPartyAuthenticator(Environment environment) {
        for (Party party : Party.values()) {
            String key = "privacy-computing.auth.parties." + party.name().toLowerCase() + ".secret";
            String value = environment.getProperty(key, "");
            if (value != null && !value.trim().isEmpty()) {
                secrets.put(party, value.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    public String authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw unauthorized("PRIVACY_AUTH_REQUIRED", "Basic credentials are required");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw unauthorized("PRIVACY_AUTH_INVALID", "invalid Basic credentials");
        }
        int separator = decoded.indexOf(':');
        if (separator <= 0) throw unauthorized("PRIVACY_AUTH_INVALID", "invalid Basic credentials");
        Party party;
        try {
            party = Party.valueOf(decoded.substring(0, separator).trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw unauthorized("PRIVACY_AUTH_INVALID", "invalid Basic credentials");
        }
        byte[] expected = secrets.get(party);
        byte[] supplied = decoded.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
        if (expected == null || !MessageDigest.isEqual(expected, supplied)) {
            throw unauthorized("PRIVACY_AUTH_INVALID", "invalid Basic credentials");
        }
        return party.name();
    }

    public String signApproval(String authenticatedParty, String canonicalDecision) {
        Party party;
        try {
            party = Party.valueOf(authenticatedParty);
        } catch (Exception ex) {
            throw new IllegalArgumentException("unknown privacy party");
        }
        byte[] secret = secrets.get(party);
        if (secret == null) throw new IllegalStateException("privacy party secret is not configured");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] signature = mac.doFinal(canonicalDecision.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : signature) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private RegistrationException unauthorized(String code, String message) {
        return new RegistrationException(HttpStatus.UNAUTHORIZED, code, message);
    }

    private enum Party { A, B, C }
}
