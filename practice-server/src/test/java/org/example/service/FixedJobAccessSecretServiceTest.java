package org.example.service;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FixedJobAccessSecretServiceTest {
    private final FixedJobAccessSecretService service = new FixedJobAccessSecretService();
    private KubernetesClient client;
    private MixedOperation<Secret, SecretList, Resource<Secret>> secrets;
    private Resource<Secret> read;
    private Resource<Secret> write;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        client = mock(KubernetesClient.class);
        secrets = mock(MixedOperation.class, RETURNS_SELF);
        read = mock(Resource.class);
        write = mock(Resource.class, RETURNS_SELF);
        when(client.secrets()).thenReturn(secrets);
        doReturn(read).when(secrets).withName(FixedJobAccessSecretService.SECRET_NAME);
        doReturn(write).when(secrets).resource(any(Secret.class));
    }

    @Test
    void updatesOnlyItsOwnKeyPreservingOtherJobsAndUsesResourceVersion() {
        Secret existing = secret("10", "other-job", "unchanged");
        when(read.get()).thenReturn(existing);

        service.putToken(client, "t4-1-2-0", "read-credential");

        ArgumentCaptor<Secret> updated = ArgumentCaptor.forClass(Secret.class);
        verify(secrets).resource(updated.capture());
        verify(secrets, times(2)).inNamespace("default");
        verify(write).lockResourceVersion("10");
        verify(write).replace();
        assertEquals("unchanged", updated.getValue().getData().get("other-job"));
        assertEquals(encoded("read-credential"), updated.getValue().getData().get("t4-1-2-0"));
        assertEquals(Collections.singletonMap("other-job", "unchanged"), existing.getData());
        assertEquals(FixedJobAccessSecretService.SECRET_NAME, updated.getValue().getMetadata().getName());
    }

    @Test
    void aConflictReReadsTheLatestSecretAndKeepsConcurrentlyAddedKeys() {
        Secret first = secret("10", "first-job", "first");
        Secret latest = new SecretBuilder(first).editMetadata().withResourceVersion("11").endMetadata()
                .addToData("concurrent-job", "concurrent").build();
        when(read.get()).thenReturn(first, latest);
        doThrow(new KubernetesClientException("conflict", 409, null)).doReturn(latest).when(write).replace();

        service.putToken(client, "t4-1-2-0", "new-credential");

        ArgumentCaptor<Secret> attempts = ArgumentCaptor.forClass(Secret.class);
        verify(secrets, times(2)).resource(attempts.capture());
        List<Secret> updates = attempts.getAllValues();
        assertFalse(updates.get(0).getData().containsKey("concurrent-job"));
        assertEquals("concurrent", updates.get(1).getData().get("concurrent-job"));
        assertEquals("first", updates.get(1).getData().get("first-job"));
        assertEquals(encoded("new-credential"), updates.get(1).getData().get("t4-1-2-0"));
        verify(write).lockResourceVersion("10");
        verify(write).lockResourceVersion("11");
    }

    @Test
    void removesOneCredentialWithoutDeletingTheSecretOrOtherCredentials() {
        when(read.get()).thenReturn(new SecretBuilder(secret("12", "other-job", "keep"))
                .addToData("t4-1-2-0", "remove").build());
        service.removeToken(client, "t4-1-2-0");
        ArgumentCaptor<Secret> updated = ArgumentCaptor.forClass(Secret.class);
        verify(secrets).resource(updated.capture());
        assertEquals(Collections.singletonMap("other-job", "keep"), updated.getValue().getData());
        verify(write).replace();
        verify(write, never()).delete();
    }

    @Test
    void missingOrImmutableSecretFailsClosedWithoutAnyWrite() {
        when(read.get()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.putToken(client, "t4-1-2-0", "credential"));
        when(read.get()).thenReturn(new SecretBuilder(secret("12", "other-job", "keep")).withImmutable(true).build());
        assertThrows(IllegalStateException.class, () -> service.putToken(client, "t4-1-2-0", "credential"));
        verify(secrets, never()).resource(any(Secret.class));
    }

    @Test
    void permissionErrorsAndExhaustedConflictsNeverExposeCredentials() {
        when(read.get()).thenReturn(secret("12", "other-job", "keep"));
        doThrow(new KubernetesClientException("body contains sensitive-credential", 403, null)).when(write).replace();
        KubernetesClientException denied = assertThrows(KubernetesClientException.class,
                () -> service.putToken(client, "t4-1-2-0", "sensitive-credential"));
        assertEquals(403, denied.getCode());
        assertFalse(denied.toString().contains("sensitive-credential"));
        assertNull(denied.getCause());
        reset(write);
        when(write.lockResourceVersion(anyString())).thenReturn(write);
        doThrow(new KubernetesClientException("sensitive-credential", 409, null)).when(write).replace();
        KubernetesClientException conflict = assertThrows(KubernetesClientException.class,
                () -> service.putToken(client, "t4-1-2-0", "sensitive-credential"));
        assertEquals(409, conflict.getCode());
        assertFalse(conflict.toString().contains("sensitive-credential"));
        verify(write, times(5)).replace();
    }

    @Test
    void unchangedOrAlreadyRemovedCredentialDoesNotWrite() {
        when(read.get()).thenReturn(secret("12", "t4-1-2-0", encoded("same")));
        service.putToken(client, "t4-1-2-0", "same");
        service.removeToken(client, "absent-job");
        verify(secrets, never()).resource(any(Secret.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bootstrapGrantsOnlyGetAndUpdateOnTheSingleSecret() throws Exception {
        List<java.util.Map<String, Object>> documents = new java.util.ArrayList<>();
        try (java.io.InputStream input = java.nio.file.Files.newInputStream(
                java.nio.file.Paths.get("k8s", "compute-input-token-secret.yaml"))) {
            new org.yaml.snakeyaml.Yaml().loadAll(input).forEach(value -> documents.add((java.util.Map<String, Object>) value));
        }
        assertEquals(3, documents.size());
        assertEquals("Secret", documents.get(0).get("kind"));
        assertEquals(Collections.emptyMap(), documents.get(0).get("data"));
        java.util.Map<String, Object> role = documents.get(1);
        List<java.util.Map<String, Object>> rules = (List<java.util.Map<String, Object>>) role.get("rules");
        assertEquals(1, rules.size());
        assertEquals(java.util.Arrays.asList("get", "update"), rules.get(0).get("verbs"));
        assertEquals(Collections.singletonList("secrets"), rules.get(0).get("resources"));
        assertEquals(Collections.singletonList(FixedJobAccessSecretService.SECRET_NAME), rules.get(0).get("resourceNames"));
        List<java.util.Map<String, Object>> subjects = (List<java.util.Map<String, Object>>) documents.get(2).get("subjects");
        assertEquals(1, subjects.size());
        assertEquals("default", subjects.get(0).get("name"));
        assertEquals("topic4-1", subjects.get(0).get("namespace"));
    }

    private Secret secret(String version, String key, String value) {
        return new SecretBuilder().withNewMetadata().withName(FixedJobAccessSecretService.SECRET_NAME)
                .withNamespace("default").withResourceVersion(version).endMetadata()
                .withType("Opaque").addToData(key, value).build();
    }

    private String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
