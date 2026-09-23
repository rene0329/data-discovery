package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.OperationResult;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatasetReplicaRemovalServiceTest {
    private static final String PATH = "/dataset/sales/sales-1.0.npz";
    private DatasetRegistrationMapper mapper;
    private NodeManagementMapper nodeMapper;
    private RegistrationAuditMapper auditMapper;
    private DatasetReplicaAvailabilityService availability;
    private DatasetUploadClient uploadClient;
    private DatasetRegistrationService service;
    private NodeManagement node;

    @BeforeEach
    void setUp() {
        mapper = mock(DatasetRegistrationMapper.class);
        nodeMapper = mock(NodeManagementMapper.class);
        auditMapper = mock(RegistrationAuditMapper.class);
        availability = mock(DatasetReplicaAvailabilityService.class);
        uploadClient = mock(DatasetUploadClient.class);
        service = new DatasetRegistrationService(mapper, nodeMapper, mock(RuntimeImageMapper.class),
                auditMapper, new ObjectMapper(), mock(RestTemplate.class), availability, uploadClient,
                mock(NodeAvailabilityService.class), mock(PlatformTransactionManager.class), 8080, "/dataset");
        when(mapper.findDatasetById(42L)).thenReturn(RegisteredDataset.builder()
                .datasetId(42L).name("sales").datasetVersion("1.0").legacyDataId(7).status("ACTIVE").build());
        node = NodeManagement.builder().nodeId(3).nodeName("storage-3").internalIp("10.0.0.3").build();
        when(nodeMapper.getNodeById(3)).thenReturn(node);
    }

    @Test
    void removesPhysicalFileThenReplicaAndCandidateRowsAndAudits() {
        DatasetReplica removed = replica(100L, 42L, 3, "AVAILABLE");
        DatasetReplica other = replica(101L, 42L, 4, "AVAILABLE");
        when(mapper.findReplicaById(100L)).thenReturn(removed);
        when(mapper.listReplicas(42L)).thenReturn(Arrays.asList(removed, other));
        usable(removed, true);
        usable(other, true);
        when(mapper.deleteCandidateByNodePath(3, PATH)).thenReturn(1);

        OperationResult result = service.removeReplica(42L, 100L, "req-1");

        assertTrue(result.isSuccess());
        assertEquals("COMPLETED", result.getStatus());
        InOrder order = inOrder(mapper, uploadClient);
        order.verify(mapper).lockDataset(42L);
        order.verify(mapper).lockStorageNode(3);
        order.verify(uploadClient).delete(node, PATH, 42L, "1.0", "replica-delete-100-req-1", null);
        order.verify(mapper).deleteReplica(100L, 42L);
        order.verify(mapper).deleteCandidateByNodePath(3, PATH);
        verify(mapper).countActiveTaskReferences(42L, "sales");
        verify(mapper).countActiveMigrationReferences(42L, 7);
        verify(mapper).countActiveSchedulingReferences(42L);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditMapper).insert(eq("DATASET"), eq("42"), eq("REMOVE_REPLICA"), anyString(),
                eq("req-1"), detail.capture());
        assertTrue(detail.getValue().contains("\"replicaId\":100"));
        assertTrue(detail.getValue().contains("\"nodeName\":\"storage-3\""));
        assertTrue(detail.getValue().contains("\"filePath\":\"" + PATH + "\""));
        assertTrue(detail.getValue().contains("\"physicalFileDeleted\":true"));
    }

    @Test
    void missingReplicaSkipsNodeCallButStillRemovesRows() {
        DatasetReplica missing = replica(100L, 42L, 3, "MISSING");
        when(mapper.findReplicaById(100L)).thenReturn(missing);
        usable(missing, false);

        service.removeReplica(42L, 100L, "req-2");

        verifyNoInteractions(uploadClient);
        verify(mapper).deleteReplica(100L, 42L);
        verify(mapper).deleteCandidateByNodePath(3, PATH);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditMapper).insert(eq("DATASET"), eq("42"), eq("REMOVE_REPLICA"), anyString(),
                eq("req-2"), detail.capture());
        assertTrue(detail.getValue().contains("\"physicalFileDeleted\":false"));
    }

    @Test
    void refusesToRemoveTheLastUsableReplica() {
        DatasetReplica removed = replica(100L, 42L, 3, "AVAILABLE");
        DatasetReplica broken = replica(101L, 42L, 4, "VERIFY_FAILED");
        when(mapper.findReplicaById(100L)).thenReturn(removed);
        when(mapper.listReplicas(42L)).thenReturn(Arrays.asList(removed, broken));
        usable(removed, true);
        usable(broken, false);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.removeReplica(42L, 100L, "req-3"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("LAST_USABLE_REPLICA", error.getErrorCode());
        assertEquals("不能删除数据集最后一个可用副本", error.getMessage());
        assertNoChanges();
    }

    @Test
    void nonUsableReplicaMayBeRemovedEvenWithoutAnotherUsableReplica() {
        DatasetReplica broken = replica(100L, 42L, 3, "VERIFY_FAILED");
        when(mapper.findReplicaById(100L)).thenReturn(broken);
        usable(broken, false);

        service.removeReplica(42L, 100L, "req-4");

        verify(uploadClient).delete(node, PATH, 42L, "1.0", "replica-delete-100-req-4", null);
        verify(mapper).deleteReplica(100L, 42L);
        verify(mapper, never()).listReplicas(any());
    }

    @Test
    void refusesWhileDatasetHasActiveTasksMigrationsOrSchedulingPlans() {
        DatasetReplica removed = replica(100L, 42L, 3, "AVAILABLE");
        when(mapper.findReplicaById(100L)).thenReturn(removed);
        when(mapper.countActiveSchedulingReferences(42L)).thenReturn(1);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.removeReplica(42L, 100L, "req-5"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("DATASET_IN_USE", error.getErrorCode());
        verify(mapper).lockDataset(42L);
        assertNoChanges();

        when(mapper.countActiveSchedulingReferences(42L)).thenReturn(0);
        when(mapper.countActiveTaskReferences(42L, "sales")).thenReturn(2);
        assertEquals("DATASET_IN_USE", assertThrows(RegistrationException.class,
                () -> service.removeReplica(42L, 100L, "req-6")).getErrorCode());
        assertNoChanges();
    }

    @Test
    void replicaOfAnotherDatasetIsNotFound() {
        when(mapper.findReplicaById(100L)).thenReturn(replica(100L, 99L, 3, "AVAILABLE"));

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.removeReplica(42L, 100L, "req-7"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals("REPLICA_NOT_FOUND", error.getErrorCode());
        verify(mapper, never()).lockDataset(any());
        assertNoChanges();
    }

    @Test
    void missingDatasetIsNotFound() {
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.removeReplica(43L, 100L, "req-8"));
        assertEquals("RESOURCE_NOT_FOUND", error.getErrorCode());
        assertNoChanges();
    }

    @Test
    void nodeDeleteFailureLeavesDatabaseUnchanged() {
        DatasetReplica removed = replica(100L, 42L, 3, "AVAILABLE");
        DatasetReplica other = replica(101L, 42L, 4, "AVAILABLE");
        when(mapper.findReplicaById(100L)).thenReturn(removed);
        when(mapper.listReplicas(42L)).thenReturn(Arrays.asList(removed, other));
        usable(removed, true);
        usable(other, true);
        doThrow(new RegistrationException(HttpStatus.BAD_GATEWAY, "DATASET_UPLOAD_FAILED",
                "source dataset deletion failed with HTTP 500"))
                .when(uploadClient).delete(any(NodeManagement.class), anyString(), nullable(Long.class),
                        nullable(String.class), nullable(String.class), nullable(String.class));

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.removeReplica(42L, 100L, "req-9"));

        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
        assertEquals("REPLICA_FILE_DELETE_FAILED", error.getErrorCode());
        assertNoChanges();
    }

    @Test
    void unregisteredNodeIsRefusedBeforeAnyChange() {
        DatasetReplica broken = replica(100L, 42L, 3, "VERIFY_FAILED");
        when(mapper.findReplicaById(100L)).thenReturn(broken);
        usable(broken, false);
        when(nodeMapper.getNodeById(3)).thenReturn(null);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.removeReplica(42L, 100L, "req-10"));

        assertEquals("REPLICA_NODE_UNAVAILABLE", error.getErrorCode());
        verifyNoInteractions(uploadClient);
        assertNoChanges();
    }

    @Test
    void longRequestIdsAreTruncatedForTheAccessAudit() {
        DatasetReplica unreachable = replica(100L, 42L, 3, "AVAILABLE");
        when(mapper.findReplicaById(100L)).thenReturn(unreachable);
        usable(unreachable, false);
        String requestId = new String(new char[128]).replace('\0', 'k');

        service.removeReplica(42L, 100L, requestId);

        ArgumentCaptor<String> accessId = ArgumentCaptor.forClass(String.class);
        verify(uploadClient).delete(eq(node), eq(PATH), eq(42L), eq("1.0"), accessId.capture(), eq((String) null));
        assertEquals(128, accessId.getValue().length());
        assertTrue(accessId.getValue().startsWith("replica-delete-100-"));
    }

    private void assertNoChanges() {
        verify(mapper, never()).deleteReplica(any(), any());
        verify(mapper, never()).deleteCandidateByNodePath(any(), any());
        verify(auditMapper, never()).insert(any(), any(), eq("REMOVE_REPLICA"), any(), any(), any());
    }

    private void usable(DatasetReplica replica, boolean usable) {
        when(availability.evaluate(replica)).thenReturn(
                new ReplicaAvailability(usable ? "USABLE" : "UNAVAILABLE", usable, null));
    }

    private DatasetReplica replica(Long replicaId, Long datasetId, Integer nodeId, String state) {
        return DatasetReplica.builder().replicaId(replicaId).datasetId(datasetId).nodeId(nodeId)
                .filePath(PATH).availability(state).build();
    }
}
