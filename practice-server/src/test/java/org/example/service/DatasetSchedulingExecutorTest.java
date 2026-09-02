package org.example.service;

import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RegisteredDataset;
import org.example.entity.SchedulingAssignment;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.SchedulingPlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DatasetSchedulingExecutorTest {
    private DatasetRegistrationMapper datasets;
    private NodeManagementMapper nodes;
    private SchedulingPlanMapper plans;
    private DatasetReplicaAvailabilityService availability;
    private NodeAvailabilityService nodeAvailability;
    private NetworkTopologyService topology;
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
        topology = mock(NetworkTopologyService.class);
        transfer = mock(DatasetUploadClient.class);
        executor = new DatasetSchedulingExecutor(datasets, nodes, plans, availability, nodeAvailability, topology, transfer);
        // Deliberately no default runtime image: data transfer must still complete.
        when(datasets.findDatasetById(10L)).thenReturn(RegisteredDataset.builder().datasetId(10L).status("ACTIVE").build());
        replica = DatasetReplica.builder().replicaId(20L).datasetId(10L).nodeId(3)
                .filePath("/dataset/test.npz").sizeBytes(123L).build();
        when(datasets.findReplicaById(20L)).thenReturn(replica);
        when(availability.evaluate(replica)).thenReturn(new ReplicaAvailability("USABLE", true, null));
        source = NodeManagement.builder().nodeId(3).type("storage").build();
        target = NodeManagement.builder().nodeId(4).type("compute-storage").build();
        when(nodes.getNodeById(3)).thenReturn(source);
        when(nodes.getNodeById(4)).thenReturn(target);
        when(nodeAvailability.isSchedulable(target)).thenReturn(true);
    }

    private void execute(String action) {
        executor.execute(40L, Collections.singletonList(SchedulingAssignment.builder()
                .assignmentId(50L).datasetId(10L).replicaId(20L).sourceNodeId(3).targetNodeId(4).action(action).build()));
    }

    @Test
    void copyRetainsSourceAndRegistersTargetWithoutRuntimeImage() {
        execute("COPY");
        verify(transfer).copyFrom(source, target, "/dataset/test.npz", 123L);
        verify(transfer).scan(target);
        verify(datasets).insertReplica(argThat(r -> r.getDatasetId().equals(10L) && r.getNodeId().equals(4)
                && r.getFilePath().equals("/dataset/test.npz") && "AVAILABLE".equals(r.getAvailability())));
        verify(transfer, never()).delete(any(), any());
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
        order.verify(transfer).copyFrom(source, target, "/dataset/test.npz", 123L);
        order.verify(transfer).scan(target);
        order.verify(datasets).insertReplica(any());
        order.verify(transfer).delete(source, "/dataset/test.npz");
        order.verify(datasets).updateReplicaAvailability(20L, "MISSING", false);
        order.verify(plans).updatePlanStatus(40L, "COMPLETED", null);
    }

    @Test
    void failedCopyDoesNotDeleteSource() {
        doThrow(new IllegalStateException("copy failed")).when(transfer).copyFrom(any(), any(), any(), any());
        execute("MOVE");
        verify(transfer, never()).delete(any(), any());
        verify(datasets, never()).insertReplica(any());
        verify(plans).updatePlanStatus(40L, "FAILED", "copy failed");
    }

    @Test
    void failedScanDoesNotDeleteSource() {
        doThrow(new IllegalStateException("scan failed")).when(transfer).scan(any());
        execute("MOVE");
        verify(transfer, never()).delete(any(), any());
        verify(plans).updatePlanStatus(40L, "FAILED", "scan failed");
    }

    @Test
    void failedRegistrationDoesNotDeleteSource() {
        doThrow(new IllegalStateException("database unavailable")).when(datasets).insertReplica(any());
        execute("MOVE");
        verify(transfer, never()).delete(any(), any());
        verify(plans).updatePlanStatus(40L, "FAILED", "database unavailable");
    }

    @Test
    void deletionFailureDoesNotReportASuccessfulMoveOrMarkSourceMissing() {
        doThrow(new IllegalStateException("delete failed")).when(transfer).delete(any(), any());
        execute("MOVE");
        verify(datasets, never()).updateReplicaAvailability(eq(20L), any(), anyBoolean());
        verify(plans).updatePlanStatus(40L, "FAILED", "delete failed");
    }

    @Test
    void copyRestoresExistingReplicaAvailability() {
        when(datasets.findReplicaByNodePath(4, "/dataset/test.npz")).thenReturn(DatasetReplica.builder()
                .replicaId(21L).datasetId(10L).availability("MISSING").build());
        execute("COPY");
        verify(datasets).updateReplicaAvailability(21L, "AVAILABLE", true);
        verify(datasets, never()).insertReplica(any());
    }

    @Test
    void refusesToOverwriteAnotherDataset() {
        when(datasets.findReplicaByNodePath(4, "/dataset/test.npz")).thenReturn(DatasetReplica.builder().datasetId(11L).build());
        execute("COPY");
        verifyNoInteractions(transfer);
        verify(plans).updatePlanStatus(40L, "FAILED", "target path belongs to another dataset");
    }

    @Test
    void rechecksAvailabilityAndNetworkBeforeCopying() {
        when(availability.evaluate(replica)).thenReturn(new ReplicaAvailability("UNREACHABLE", false, "offline"));
        execute("COPY");
        verifyNoInteractions(transfer);
        when(availability.evaluate(replica)).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(topology.requirePath(3, 4)).thenThrow(new IllegalStateException("no route"));
        execute("COPY");
        verifyNoInteractions(transfer);
        verify(plans).updatePlanStatus(40L, "FAILED", "no route");
    }
}
