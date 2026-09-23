package org.example.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileIntegrityResultJsonTest {

    @Test
    void readsTheAgentCopyFromResponseIncludingLegacyFields() throws Exception {
        // Verbatim response of /data-discovery/copy-from that failed scheduling plan 48.
        String response = "{\"path\":\"catdog/npz/catdog.npz\",\"size\":67833558,"
                + "\"digest\":\"71f2de134daf04992491df3ab8488940460280578b4e9cf09a11945d529d2704\","
                + "\"verified\":true,\"status\":\"ok\",\"sizeBytes\":67833558,\"algorithm\":\"SHA-256\"}";

        FileIntegrityResult result = new ObjectMapper().readValue(response, FileIntegrityResult.class);

        assertEquals("catdog/npz/catdog.npz", result.getPath());
        assertEquals(67833558L, result.getSizeBytes());
        assertEquals("SHA-256", result.getAlgorithm());
        assertEquals("71f2de134daf04992491df3ab8488940460280578b4e9cf09a11945d529d2704", result.getDigest());
        assertTrue(result.isVerified());
    }
}
