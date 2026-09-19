package org.example.security.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

@Component
public class DatasetAccessTokenCodec {
    private static final String PREFIX = "t4v1";
    private final ObjectMapper objectMapper;
    private final DatasetAccessProperties properties;
    private final Clock clock;

    @Autowired
    public DatasetAccessTokenCodec(ObjectMapper objectMapper, DatasetAccessProperties properties) {
        this(objectMapper, properties, Clock.systemUTC());
    }

    DatasetAccessTokenCodec(ObjectMapper objectMapper, DatasetAccessProperties properties, Clock clock) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    public String encode(AccessTokenClaims claims) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsBytes(claims));
            String signed = PREFIX + "." + payload;
            return signed + "." + sign(signed);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to create scoped access token", ex);
        }
    }

    public AccessTokenClaims decodeAndVerify(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw invalid("TOKEN_MISSING", "scoped access token is required");
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw invalid("TOKEN_MALFORMED", "scoped access token is malformed");
        }
        String signed = parts[0] + "." + parts[1];
        if (!constantTimeEquals(sign(signed), parts[2])) {
            throw invalid("TOKEN_SIGNATURE_INVALID", "scoped access token signature is invalid");
        }
        try {
            AccessTokenClaims claims = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), AccessTokenClaims.class);
            if (claims.getExpiresAtEpochSeconds() <= clock.instant().getEpochSecond()) {
                throw invalid("TOKEN_EXPIRED", "scoped access token has expired");
            }
            if (claims.getJti() == null || claims.getJti().isEmpty()
                    || claims.getSubject() == null || claims.getSubject().isEmpty()) {
                throw invalid("TOKEN_CLAIMS_INVALID", "scoped access token claims are incomplete");
            }
            return claims;
        } catch (AccessAuthorizationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid("TOKEN_MALFORMED", "scoped access token payload is invalid");
        }
    }

    private String sign(String signed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getHmacSecret()
                    .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private AccessAuthorizationException invalid(String code, String message) {
        return new AccessAuthorizationException(HttpStatus.UNAUTHORIZED, code, message);
    }
}
