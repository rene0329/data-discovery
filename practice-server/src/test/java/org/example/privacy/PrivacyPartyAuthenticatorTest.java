package org.example.privacy;

import org.example.exception.RegistrationException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrivacyPartyAuthenticatorTest {
    @Test
    void derivesPartyFromBasicCredentialsAndSignsWithIndependentSecret() {
        PrivacyPartyAuthenticator authenticator = authenticator();

        assertEquals("A", authenticator.authenticate(basic("A", "secret-a-123456")));
        String a = authenticator.signApproval("A", "job\nattempt\nAPPROVED");
        String b = authenticator.signApproval("B", "job\nattempt\nAPPROVED");

        assertEquals(64, a.length());
        assertEquals(64, b.length());
        assertNotEquals(a, b);
    }

    @Test
    void rejectsUnknownOrIncorrectCredentials() {
        PrivacyPartyAuthenticator authenticator = authenticator();

        assertThrows(RegistrationException.class,
                () -> authenticator.authenticate(basic("A", "wrong")));
        assertThrows(RegistrationException.class,
                () -> authenticator.authenticate(basic("D", "secret-a-123456")));
        assertThrows(RegistrationException.class,
                () -> authenticator.authenticate(null));
    }

    private PrivacyPartyAuthenticator authenticator() {
        return new PrivacyPartyAuthenticator(new MockEnvironment()
                .withProperty("privacy-computing.auth.parties.a.secret", "secret-a-123456")
                .withProperty("privacy-computing.auth.parties.b.secret", "secret-b-123456")
                .withProperty("privacy-computing.auth.parties.c.secret", "secret-c-123456"));
    }

    private String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
