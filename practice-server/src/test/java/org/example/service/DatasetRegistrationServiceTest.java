package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisterDatasetRequest;
import org.example.dto.registration.UploadDatasetRequest;
import org.example.entity.DatasetDiscoveryCandidate;
import org.example.entity.DatasetReplica;
import org.example.entity.DatasetMetadata;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class DatasetRegistrationServiceTest {
    private DatasetRegistrationMapper mapper;
    private NodeManagementMapper nodeMapper;
    private RestTemplate restTemplate;
    private DatasetRegistrationService service;
    private DatasetReplicaAvailabilityService replicaAvailabilityService;
    private DatasetUploadClient uploadClient;
    private NodeAvailabilityService nodeAvailabilityService;

    @BeforeEach
    void setUp() {
        mapper = mock(DatasetRegistrationMapper.class);
        nodeMapper = mock(NodeManagementMapper.class);
        restTemplate = mock(RestTemplate.class);
        replicaAvailabilityService = mock(DatasetReplicaAvailabilityService.class);
        uploadClient = mock(DatasetUploadClient.class);
        nodeAvailabilityService = mock(NodeAvailabilityService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DatasetRegistrationService(mapper, nodeMapper, mock(RuntimeImageMapper.class),
                mock(RegistrationAuditMapper.class), new ObjectMapper(), restTemplate,
                replicaAvailabilityService, uploadClient, nodeAvailabilityService,
                transactionManager, 8080, "/dataset");
    }

    @Test
    void unregisterSoftDeletesWithoutRemovingSourceFilesOrReplicaHistory() {
        when(mapper.findDatasetById(42L)).thenReturn(RegisteredDataset.builder()
                .datasetId(42L).name("renamed-dataset").status("DRAFT").build());

        service.unregister(42L, "delete-request");

        verify(mapper).countTaskReferences(42L, "renamed-dataset");
        verify(mapper).countActiveMigrationReferences(42L, null);
        verify(mapper).countActiveSchedulingReferences(42L);
        verify(mapper).softDeleteDataset(42L);
        org.mockito.Mockito.verifyNoInteractions(uploadClient);
    }

    @Test
    void unregisterRejectsTaskReferencesUsingTheStableDatasetId() {
        when(mapper.findDatasetById(42L)).thenReturn(RegisteredDataset.builder()
                .datasetId(42L).name("renamed-dataset").build());
        when(mapper.countTaskReferences(42L, "renamed-dataset")).thenReturn(1);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.unregister(42L, "delete-request"));

        assertEquals("DATASET_IN_USE", error.getErrorCode());
        verify(mapper, never()).softDeleteDataset(any());
    }

    @Test
    void unregisterRejectsActiveMigrationIncludingLegacyDataReferences() {
        when(mapper.findDatasetById(42L)).thenReturn(RegisteredDataset.builder()
                .datasetId(42L).legacyDataId(7).name("data").build());
        when(mapper.countActiveMigrationReferences(42L, 7)).thenReturn(1);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.unregister(42L, "delete-request"));

        assertEquals("DATASET_IN_USE", error.getErrorCode());
        verify(mapper, never()).softDeleteDataset(any());
    }

    @Test
    void unregisterRejectsAcceptedOrRunningSchedulingPlans() {
        when(mapper.findDatasetById(42L)).thenReturn(RegisteredDataset.builder()
                .datasetId(42L).name("data").build());
        when(mapper.countActiveSchedulingReferences(42L)).thenReturn(1);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.unregister(42L, "delete-request"));

        assertEquals("DATASET_IN_USE", error.getErrorCode());
        verify(mapper, never()).softDeleteDataset(any());
    }

    @Test
    void unregisterReturnsNotFoundForMissingOrDeletedDataset() {
        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.unregister(42L, "delete-request"));
        assertEquals("RESOURCE_NOT_FOUND", error.getErrorCode());
        verify(mapper, never()).softDeleteDataset(any());
    }

    @Test
    void discoveryUsesVerifiedStorageNodesEvenWhenSchedulingIsDisabled() {
        NodeManagement storage = NodeManagement.builder().nodeId(1).nodeName("storage-1")
                .internalIp("10.0.0.1").type("storage").registrationStatus("REGISTERED")
                .enabled(false).observedStatus("ONLINE").verifiedAt(LocalDateTime.now()).build();
        NodeManagement compute = NodeManagement.builder().nodeId(2).nodeName("compute-1")
                .internalIp("10.0.0.2").type("compute").registrationStatus("REGISTERED")
                .enabled(false).observedStatus("ONLINE").verifiedAt(LocalDateTime.now()).build();
        when(nodeMapper.listRegisteredNodes(null, null, null))
                .thenReturn(Arrays.asList(storage, compute));
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        when(restTemplate.getForEntity(anyString(), org.mockito.ArgumentMatchers.eq(Map.class)))
                .thenReturn(ResponseEntity.ok(response));

        OperationResult result = service.discover(Collections.emptySet());

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(1, result.getRequestedCount());
        assertEquals(1, result.getProcessedCount());
    }

    @Test
    void registrationRejectsPhysicalFileAlreadyOwnedByAnotherDataset() {
        RegisterDatasetRequest request = new RegisterDatasetRequest();
        request.setCandidateId(9L);
        request.setDatasetCode("sales");
        request.setName("sales");
        request.setVersion("1.0");
        request.setDataType("NPZ");
        DatasetDiscoveryCandidate candidate = DatasetDiscoveryCandidate.builder()
                .candidateId(9L).nodeId(1).filePath("/dataset/sales.npz")
                .availability("AVAILABLE").build();
        when(mapper.findCandidateById(9L)).thenReturn(candidate);
        when(mapper.findReplicaByNodePath(1, "/dataset/sales.npz"))
                .thenReturn(DatasetReplica.builder().replicaId(3L).datasetId(2L).build());

        assertThrows(RegistrationException.class, () -> service.register(request, "request-1"));
    }

    @Test
    void registrationUsesCompanionMetadataJson() {
        String metadataJson = "{\"metadataVersion\":\"1.0\","
                + "\"dataset\":{\"datasetCode\":\"mnist\",\"name\":\"MNIST\",\"version\":\"1.0\","
                + "\"category\":\"IMAGE\",\"format\":\"NPZ\"},"
                + "\"schema\":{\"type\":\"TENSOR\"},\"profile\":{\"sampleCount\":10},"
                + "\"schedulingHints\":{\"requiredResources\":{\"cpu\":2,\"memoryGi\":4,\"gpu\":0}}}";
        RegisterDatasetRequest request = new RegisterDatasetRequest();
        request.setCandidateId(9L);
        DatasetDiscoveryCandidate candidate = DatasetDiscoveryCandidate.builder()
                .candidateId(9L).nodeId(1).filePath("/dataset/mnist-1.0.npz")
                .metadataJson(metadataJson).availability("AVAILABLE").build();
        when(mapper.findCandidateById(9L)).thenReturn(candidate);
        when(nodeMapper.getNodeById(1)).thenReturn(NodeManagement.builder().nodeId(1).build());
        doAnswer(invocation -> {
            RegisteredDataset value = invocation.getArgument(0);
            value.setDatasetId(44L);
            return 1;
        }).when(mapper).insertDataset(any(RegisteredDataset.class));
        when(mapper.findDatasetById(44L)).thenReturn(RegisteredDataset.builder()
                .datasetId(44L).datasetCode("mnist").name("MNIST").datasetVersion("1.0")
                .category("IMAGE").dataFormat("NPZ").status("DRAFT").build());
        when(mapper.listReplicas(44L)).thenReturn(Collections.emptyList());

        service.register(request, "metadata-request");

        ArgumentCaptor<RegisteredDataset> datasetCaptor = ArgumentCaptor.forClass(RegisteredDataset.class);
        verify(mapper).insertDataset(datasetCaptor.capture());
        assertEquals("IMAGE", datasetCaptor.getValue().getCategory());
        assertEquals(2.0, datasetCaptor.getValue().getRequiredCpu());
        ArgumentCaptor<DatasetMetadata> metadataCaptor = ArgumentCaptor.forClass(DatasetMetadata.class);
        verify(mapper).upsertDatasetMetadata(metadataCaptor.capture());
        assertEquals("{\"sampleCount\":10}", metadataCaptor.getValue().getProfileJson());
    }

    @Test
    void datasetViewSummarizesReplicaBusinessHealth() {
        RegisteredDataset dataset = RegisteredDataset.builder().datasetId(5L)
                .datasetCode("sales").name("sales").status("ACTIVE").build();
        DatasetReplica usable = DatasetReplica.builder().replicaId(1L).nodeId(1)
                .availability("AVAILABLE").build();
        DatasetReplica unreachable = DatasetReplica.builder().replicaId(2L).nodeId(2)
                .availability("AVAILABLE").build();
        when(mapper.listDatasets("", null)).thenReturn(Collections.singletonList(dataset));
        when(mapper.listReplicas(5L)).thenReturn(Arrays.asList(usable, unreachable));
        doAnswer(invocation -> {
            DatasetReplica replica = invocation.getArgument(0);
            if (replica.getReplicaId().equals(1L)) replica.setEffectiveAvailability("USABLE");
            else {
                replica.setEffectiveAvailability("UNREACHABLE");
                replica.setStatusReason("节点未启用");
            }
            return null;
        }).when(replicaAvailabilityService).enrich(org.mockito.ArgumentMatchers.any(DatasetReplica.class));

        org.example.dto.registration.RegisteredDatasetView view =
                service.listDatasets("", null).get(0);

        assertEquals("DEGRADED", view.getHealthStatus());
        assertEquals(1, view.getAvailableReplicaCount());
        assertEquals(2, view.getTotalReplicaCount());
        assertEquals("节点未启用", view.getStatusReason());
    }

    @Test
    void uploadRejectsNonStorageNodeBeforeSendingFile() {
        UploadDatasetRequest request = uploadRequest();
        NodeManagement compute = NodeManagement.builder().nodeId(1).nodeName("compute-1")
                .internalIp("10.0.0.1").type("compute").registrationStatus("ACTIVE")
                .enabled(true).observedStatus("ONLINE").build();
        when(nodeMapper.getNodeById(1)).thenReturn(compute);
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales.npz", "application/octet-stream", new byte[]{1, 2, 3});

        assertThrows(RegistrationException.class,
                () -> service.uploadAndRegister(request, file, "upload-request-1"));

        verify(uploadClient, never()).upload(any(), any(), anyString());
    }

    @Test
    void uploadRegistersDiscoveredFileAsFirstReplica() {
        UploadDatasetRequest request = uploadRequest();
        request.setDataType(null);
        NodeManagement storage = NodeManagement.builder().nodeId(1).nodeName("storage-1")
                .internalIp("10.0.0.1").type("storage").registrationStatus("ACTIVE")
                .enabled(true).observedStatus("ONLINE").build();
        when(nodeMapper.getNodeById(1)).thenReturn(storage);
        when(nodeAvailabilityService.evaluate(storage))
                .thenReturn(new NodeAvailability("AVAILABLE", true, null));

        DatasetDiscoveryCandidate candidate = DatasetDiscoveryCandidate.builder()
                .candidateId(9L).nodeId(1)
                .filePath("/dataset/uploads/sales/1.0/sales-1.0.npz")
                .fileName("sales-1.0.npz").fileType("NPZ").sizeBytes(3L)
                .availability("AVAILABLE").lastSeenAt(LocalDateTime.now()).build();
        when(mapper.findCandidateByNodePath(1, candidate.getFilePath())).thenReturn(candidate);
        when(mapper.findCandidateById(9L)).thenReturn(candidate);
        when(mapper.findDatasetByCodeAndVersion("sales", "1.0")).thenReturn(null);
        when(mapper.findReplicaByNodePath(1, candidate.getFilePath())).thenReturn(null);
        doAnswer(invocation -> {
            RegisteredDataset inserted = invocation.getArgument(0);
            inserted.setDatasetId(44L);
            return 1;
        }).when(mapper).insertDataset(any(RegisteredDataset.class));
        doAnswer(invocation -> {
            DatasetReplica inserted = invocation.getArgument(0);
            inserted.setReplicaId(55L);
            return 1;
        }).when(mapper).insertReplica(any(DatasetReplica.class));
        RegisteredDataset saved = RegisteredDataset.builder().datasetId(44L)
                .datasetCode("sales").datasetVersion("1.0").name("Sales")
                .dataType("NPZ").status("DRAFT").rowVersion(0).build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(55L).datasetId(44L)
                .nodeId(1).filePath(candidate.getFilePath()).sizeBytes(3L)
                .availability("AVAILABLE").build();
        when(mapper.findDatasetById(44L)).thenReturn(saved);
        when(mapper.listReplicas(44L)).thenReturn(Collections.singletonList(replica));
        doAnswer(invocation -> {
            DatasetReplica item = invocation.getArgument(0);
            item.setEffectiveAvailability("USABLE");
            return null;
        }).when(replicaAvailabilityService).enrich(any(DatasetReplica.class));
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales.npz", "application/octet-stream", new byte[]{1, 2, 3});

        org.example.dto.registration.RegisteredDatasetView result =
                service.uploadAndRegister(request, file, "upload-request-2");

        assertEquals(44L, result.getDatasetId());
        assertEquals(1, result.getTotalReplicaCount());
        ArgumentCaptor<RegisteredDataset> datasetCaptor = ArgumentCaptor.forClass(RegisteredDataset.class);
        verify(mapper).insertDataset(datasetCaptor.capture());
        assertEquals("NPZ", datasetCaptor.getValue().getDataType());
        verify(uploadClient).upload(eq(storage), eq(file),
                eq("uploads/sales/1.0/sales-1.0.npz"));
        verify(uploadClient).scan(storage);
        verify(uploadClient, never()).deleteQuietly(any(), anyString());
    }

    @Test
    void uploadRejectsDotPathSegmentBeforeSendingFile() {
        UploadDatasetRequest request = uploadRequest();
        request.setVersion("..");
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales.npz", "application/octet-stream", new byte[]{1});

        assertThrows(RegistrationException.class,
                () -> service.uploadAndRegister(request, file, "upload-request-invalid"));

        verify(uploadClient, never()).upload(any(), any(), anyString());
    }

    private UploadDatasetRequest uploadRequest() {
        UploadDatasetRequest request = new UploadDatasetRequest();
        request.setNodeId(1);
        request.setDatasetCode("sales");
        request.setName("Sales");
        request.setVersion("1.0");
        request.setDataType("NPZ");
        return request;
    }
}
