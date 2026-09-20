package org.example.privacy;

import org.example.exception.RegistrationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Server-side integrity signature for canonical privacy approval evidence. */
@Component
public class PrivacyApprovalSigner {
    private final byte[] key;

    public PrivacyApprovalSigner(@Value("${app.auth.jwt.signing-key:}") String signingKey) {
        String value = signingKey == null ? "" : signingKey.trim();
        this.key = value.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String canonicalDecision) {
        if (key.length < 32) {
            throw RegistrationException.conflict("APPROVAL_SIGNING_NOT_CONFIGURED",
                    "approval signing key must contain at least 32 characters");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signature = mac.doFinal(canonicalDecision.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(signature.length * 2);
            for (byte item : signature) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }
}
