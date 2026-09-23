package org.example.service;

import org.example.entity.DatasetReplica;
import org.example.entity.DatasetMetadata;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.SchedulingAssignment;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.example.model.FileIntegrityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Collections;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DatasetSchedulingExecutorTest {
    private static final String SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private DatasetRegistrationMapper datasets;
    private NodeManagementMapper nodes;
    private SchedulingPlanMapper plans;
    private DatasetReplicaAvailabilityService availability;
    private NodeAvailabilityService nodeAvailability;
    private DatasetUploadClient transfer;
    private DatasetSchedulingExecutor executor;
    private DatasetReplica replica;
    private NodeManagement source;
    private NodeManagement target;

    @BeforeEach
    void setup() {
        datasets = mock(DatasetRegistrationMapper.class);
        nodes = mock(NodeManagementMapper.class);
        plans = mock(SchedulingPlanMapper.class);
        availability = mock(DatasetReplicaAvailabilityService.class);
        nodeAvailability = mock(NodeAvailabilityService.class);
        transfer = mock(DatasetUploadClient.class);
        executor = new DatasetSchedulingExecutor(datasets, nodes, plans, availability, nodeAvailability, transfer);
        // Deliberately no default runtime image: data transfer must still complete.
        when(datasets.findDatasetById(10L)).thenReturn(RegisteredDataset.builder().datasetId(10L)
                .datasetVersion("1.0").status("ACTIVE").build());
        replica = DatasetReplica.builder().replicaId(20L).datasetId(10L).nodeId(3)
                .filePath("/dataset/test.npz").sizeBytes(123L).checksumAlgorithm("SHA-256")
                .checksum(SHA256).availability("AVAILABLE").verifiedAt(LocalDateTime.now()).build();
        when(datasets.findReplicaById(20L)).thenReturn(replica);
        when(datasets.findDatasetMetadata(10L)).thenReturn(DatasetMetadata.builder()
                .datasetId(10L).authoritativeSizeBytes(123L)
                .digestAlgorithm("SHA-256").digestValue(SHA256).build());
        when(availability.evaluate(replica)).thenReturn(new ReplicaAvailability("USABLE", true, null));
        source = NodeManagement.builder().nodeId(3).type("storage").build();
        target = NodeManagement.builder().nodeId(4).type("compute-storage").build();
        when(nodes.getNodeById(3)).thenReturn(source);
        when(nodes.getNodeById(4)).thenReturn(target);
        when(nodeAvailability.isSchedulable(target)).thenReturn(true);
        FileIntegrityResult verified = new FileIntegrityResult("/dataset/test.npz", 123L,
                "SHA-256", SHA256, true, "verified");
        when(transfer.verify(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(verified);
        when(transfer.copyFrom(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(verified);
        doAnswer(invocation -> {
            DatasetReplica inserted = invocation.getArgument(0);
            inserted.setReplicaId(21L);
            return 1;
        }).when(datasets).insertReplica(any());
    }

    private void execute(String action) {
        executor.execute(40L, Collections.singletonList(SchedulingAssignment.builder()
                .planId(40L).assignmentId(50L).datasetId(10L).replicaId(20L).sourceNodeId(3).targetNodeId(4).action(action).build()));
    }

    @Test
    void copyRetainsSourceAndRegistersTargetWithoutRuntimeImage() {
        execute("COPY");
        verify(transfer).copyFrom(source, target, "/dataset/test.npz", 123L, SHA256,
                10L, "1.0", "storage-plan-40-assignment-50");
        verify(transfer).scan(target);
        verify(datasets).insertReplica(argThat(r -> r.getDatasetId().equals(10L) && r.getNodeId().equals(4)
                && r.getFilePath().equals("/dataset/test.npz") && "VERIFYING".equals(r.getAvailability())));
        verify(datasets).updateReplicaIntegrity(eq(21L), eq(123L), eq("SHA-256"), eq(SHA256),
                eq("AVAILABLE"), anyString(), eq(true));
        verify(transfer, never()).delete(any(), any(), any(), any(), any(), any());
        verify(plans).updateAssignmentStatus(50L, "COMPLETED", null);
        verify(plans).updatePlanStatus(40L, "COMPLETED", null);
    }

    @Test
    void failedStatusWriteDoesNotReportCompletedPlan() {
        doThrow(new IllegalStateException("status write failed")).when(plans).updateAssignmentStatus(50L, "COMPLETED", null);
        execute("COPY");
        verify(plans).updatePlanStatus(40L, "FAILED", "status write failed");
        verify(plans, never()).updatePlanStatus(40L, "COMPLETED", null);
    }

    @Test
    void moveDeletesOnlyAfterSuccessfulCopyScanAndRegistration() {
        execute("MOVE");
        InOrder order = inOrder(transfer, datasets, plans);
        order.verify(datasets).insertReplica(any());
        order.verify(transfer).copyFrom(source, target, "/dataset/test.npz", 123L, SHA256,
                10L, "1.0", "storage-plan-40-assignment-50");
        order.verify(transfer).scan(target);
        order.verify(datasets).updateReplicaIntegrity(eq(21L), eq(123L), eq("SHA-256"),
                eq(SHA256), eq("AVAILABLE"), anyString(), eq(true));
        order.verify(transfer).delete(source, "/dataset/test.npz", 10L, "1.0",
                "storage-plan-40-assignment-50", null);
        order.verify(datasets).updateReplicaAvailability(20L, "MISSING", false);
        order.verify(plans).updatePlanStatus(40L, "COMPLETED", null);
    }

    @Test
    void failedCopyDoesNotDeleteSource() {
        doThrow(new IllegalStateException("copy failed")).when(transfer)
                .copyFrom(any(), any(), any(), any(), any(), any(), any(), any());
        execute("MOVE");
        verify(transfer, never()).delete(any(), any(), any(), any(), any(), any());
        verify(datasets).insertReplica(argThat(r -> "VERIFYING".equals(r.getAvailability())));
        verify(plans).updatePlanStatus(40L, "FAILED", "copy failed");
    }

    @Test
    void failedScanDoesNotDeleteSource() {
        doThrow(new IllegalStateException("scan failed")).when(transfer).scan(any());
        execute("MOVE");
        verify(transfer, never()).delete(any(), any(), any(), any(), any(), any());
        verify(plans).updatePlanStatus(40L, "FAILED", "scan failed");
    }

    @Test
    void failedRegistrationDoesNotDeleteSource() {
        doThrow(new IllegalStateException("database unavailable")).when(datasets).insertReplica(any());
        execute("MOVE");
        verify(transfer, never()).delete(any(), any(), any(), any(), any(), any());
        verify(plans).updatePlanStatus(40L, "FAILED", "database unavailable");
    }

    @Test
    void deletionFailureDoesNotReportASuccessfulMoveOrMarkSourceMissing() {
        doThrow(new IllegalStateException("delete failed")).when(transfer)
                .delete(any(), any(), any(), any(), any(), any());
        execute("MOVE");
        verify(datasets, never()).updateReplicaAvailability(eq(20L), any(), anyBoolean());
        verify(plans).updatePlanStatus(40L, "FAILED", "delete failed");
    }

    @Test
    void copyRestoresExistingReplicaAvailability() {
        when(datasets.findReplicaByNodePath(4, "/dataset/test.npz")).thenReturn(DatasetReplica.builder()
                .replicaId(21L).datasetId(10L).availability("MISSING").build());
        execute("COPY");
        verify(datasets).updateReplicaIntegrity(eq(21L), eq(123L), eq("SHA-256"), eq(SHA256),
                eq("AVAILABLE"), anyString(), eq(true));
        verify(datasets, never()).insertReplica(any());
    }

    @Test
    void occupancyAppearingDuringCopyPreventsSourceDeletion() {
        when(datasets.countActiveTaskReferences(10L, null)).thenReturn(0, 1);
        execute("MOVE");
        verify(transfer).copyFrom(source, target, "/dataset/test.npz", 123L, SHA256,
                10L, "1.0", "storage-plan-40-assignment-50");
        verify(transfer, never()).delete(any(), any(), any(), any(), any(), any());
        verify(datasets, never()).updateReplicaAvailability(20L, "MISSING", false);
        verify(plans).updatePlanStatus(eq(40L), eq("FAILED"), anyString());
    }

    @Test
    void overlappingPlanStopsTransferButOwnPlanDoesNotBlockItself() {
        when(datasets.countOtherSchedulingReferences(10L, 40L)).thenReturn(1);
        execute("COPY");
        verifyNoInteractions(transfer);
        verify(plans).updatePlanStatus(eq(40L), eq("FAILED"), anyString());
    }

    @Test
    void refusesToOverwriteAnotherDataset() {
        when(datasets.findReplicaByNodePath(4, "/dataset/test.npz")).thenReturn(DatasetReplica.builder().datasetId(11L).build());
        execute("COPY");
        verifyNoInteractions(transfer);
        verify(plans).updatePlanStatus(40L, "FAILED", "target path belongs to another dataset");
    }

    @Test
    void rechecksAvailabilityBeforeCopying() {
        when(availability.evaluate(replica)).thenReturn(new ReplicaAvailability("UNREACHABLE", false, "offline"));
        execute("COPY");
        verifyNoInteractions(transfer);
    }

    @Test
    void deleteRemovesOnlyOneOfMultipleAvailableReplicas() {
        DatasetReplica remaining = DatasetReplica.builder().replicaId(21L).datasetId(10L)
                .nodeId(4).availability("AVAILABLE").build();
        when(datasets.listReplicas(10L)).thenReturn(java.util.Arrays.asList(replica, remaining));
        when(availability.evaluate(remaining)).thenReturn(new ReplicaAvailability("USABLE", true, null));

        execute("DELETE");

        verify(transfer).delete(source, "/dataset/test.npz", 10L, "1.0",
                "storage-plan-40-assignment-50", null);
        verify(datasets).updateReplicaAvailability(20L, "MISSING", false);
        verify(plans).updatePlanStatus(40L, "COMPLETED", null);
    }

    @Test
    void deleteRefusesToRemoveLastAvailableReplica() {
        when(datasets.listReplicas(10L)).thenReturn(Collections.singletonList(replica));

        execute("DELETE");

        verify(transfer, never()).delete(any(), any(), any(), any(), any(), any());
        verify(datasets, never()).updateReplicaAvailability(20L, "MISSING", false);
        verify(plans).updatePlanStatus(40L, "FAILED", "cannot delete the last available replica");
    }

    @Test
    void deleteRefusesWhenOtherReplicaIsLabelledAvailableButUnusable() {
        DatasetReplica offline = DatasetReplica.builder().replicaId(21L).datasetId(10L)
                .nodeId(4).availability("AVAILABLE").build();
        when(datasets.listReplicas(10L)).thenReturn(java.util.Arrays.asList(replica, offline));
        when(availability.evaluate(offline)).thenReturn(
                new ReplicaAvailability("UNREACHABLE", false, "node offline"));

        execute("DELETE");

        verify(transfer, never()).delete(any(), any(), any(), any(), any(), any());
        verify(datasets, never()).updateReplicaAvailability(20L, "MISSING", false);
        verify(plans).updatePlanStatus(40L, "FAILED", "cannot delete the last available replica");
    }
}
