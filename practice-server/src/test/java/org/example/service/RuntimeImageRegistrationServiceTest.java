package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.RuntimeImageView;
import org.example.entity.RuntimeImage;
import org.example.exception.RegistrationException;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeImageRegistrationServiceTest {
    private RuntimeImageMapper mapper;
    private RuntimeImagePullVerifier verifier;
    private RuntimeImageRegistrationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(RuntimeImageMapper.class);
        RegistrationAuditMapper auditMapper = mock(RegistrationAuditMapper.class);
        verifier = mock(RuntimeImagePullVerifier.class);
        service = new RuntimeImageRegistrationService(mapper, auditMapper, verifier, new ObjectMapper());
    }

    @Test
    void verifyMovesImageToReadyAndStoresDigest() {
        RuntimeImage draft = image("DRAFT", null);
        RuntimeImage ready = image("READY", "sha256:abc");
        when(mapper.findById(7L)).thenReturn(draft, ready);
        when(verifier.verify("registry/acme/train:v1", null))
                .thenReturn(new RuntimeImagePullVerifier.VerificationResult("sha256:abc", "verified"));

        RuntimeImageView result = service.verify(7L, "request-1");

        assertEquals("READY", result.getStatus());
        assertEquals("sha256:abc", result.getResolvedDigest());
        verify(mapper).updateStatus(7L, "READY", false, "sha256:abc", "verified", true);
    }

    @Test
    void verifyMovesImageToInvalidWhenPullFails() {
        when(mapper.findById(7L)).thenReturn(image("DRAFT", null));
        when(verifier.verify("registry/acme/train:v1", null))
                .thenThrow(new IllegalStateException("ImagePullBackOff"));

        assertThrows(RegistrationException.class, () -> service.verify(7L, "request-2"));
        verify(mapper).updateStatus(eq(7L), eq("INVALID"), eq(false), eq(null),
                eq("IMAGE_PULL_FAILED"), eq(false));
    }

    private RuntimeImage image(String status, String digest) {
        return RuntimeImage.builder()
                .runtimeImageId(7L)
                .name("trainer")
                .imageRef("registry/acme/train:v1")
                .commandJson("[]")
                .argsTemplateJson("[]")
                .status(status)
                .enabled(false)
                .resolvedDigest(digest)
                .rowVersion(0)
                .build();
    }
}
