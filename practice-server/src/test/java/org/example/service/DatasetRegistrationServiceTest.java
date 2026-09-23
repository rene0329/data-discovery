package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.auth.AuthMapper;
import org.example.auth.AuthenticatedUser;
import org.example.auth.CollaborationDomain;
import org.example.auth.CurrentUserService;
import org.example.dto.registration.OperationResult;
import org.example.dto.registration.RegisterDatasetRequest;
import org.example.dto.registration.RegisteredDatasetView;
import org.example.dto.registration.UpdateDatasetRequest;
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
import org.example.model.FileIntegrityResult;
import org.example.security.access.DatasetDomainLocation;
import org.example.security.access.DatasetDomainMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class DatasetRegistrationServiceTest {
    private static final String SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private DatasetRegistrationMapper mapper;
    private NodeManagementMapper nodeMapper;
    private RestTemplate restTemplate;
    private DatasetRegistrationService service;
    private DatasetReplicaAvailabilityService replicaAvailabilityService;
    private DatasetUploadClient uploadClient;
    private NodeAvailabilityService nodeAvailabilityService;
    private DatasetDomainMapper locations;
    private AuthMapper domains;
    private RuntimeImageMapper runtimeImageMapper;

    @BeforeEach
    void setUp() {
        mapper = mock(DatasetRegistrationMapper.class);
        nodeMapper = mock(NodeManagementMapper.class);
        restTemplate = mock(RestTemplate.class);
        replicaAvailabilityService = mock(DatasetReplicaAvailabilityService.class);
        uploadClient = mock(DatasetUploadClient.class);
        when(uploadClient.verify(any(NodeManagement.class), anyString(),
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(Long.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenAnswer(invocation -> {
                    Long size = invocation.getArgument(2);
                    String expected = invocation.getArgument(3);
                    return new FileIntegrityResult(invocation.getArgument(1),
                            size == null ? 3L : size, "SHA-256", SHA256,
                            expected != null, expected == null ? "expected SHA-256 is required" : "verified");
                });
        nodeAvailabilityService = mock(NodeAvailabilityService.class);
        locations = mock(DatasetDomainMapper.class);
        domains = mock(AuthMapper.class);
        runtimeImageMapper = mock(RuntimeImageMapper.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DatasetRegistrationService(mapper, nodeMapper, runtimeImageMapper,
                mock(RegistrationAuditMapper.class), new ObjectMapper(), restTemplate,
                replicaAvailabilityService, uploadClient, nodeAvailabilityService,
                locations, domains, transactionManager, 8080, "/dataset");
        when(domains.findDomainBySiteCode("sh")).thenReturn(domain(1L, "sh", true));
        when(domains.findDomainBySiteCode("sz")).thenReturn(domain(2L, "sz", true));
        when(domains.findDomainBySiteCode("bj")).thenReturn(domain(3L, "bj", false));
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
        assertEquals("SHA-256", metadataCaptor.getValue().getDigestAlgorithm());
        assertEquals(SHA256, metadataCaptor.getValue().getDigestValue());
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

        verify(uploadClient, never()).upload(any(), any(), anyString(),
                any(), any(), any(), any());
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
                eq("uploads/sales/1.0/sales-1.0.npz"), eq(null), eq("1.0"),
                eq("upload-request-2"), eq(null));
        verify(uploadClient).scan(storage);
        verify(uploadClient, never()).deleteQuietly(any(), anyString(),
                any(), any(), any(), any());
    }

    @Test
    void uploadRejectsDotPathSegmentBeforeSendingFile() {
        UploadDatasetRequest request = uploadRequest();
        request.setVersion("..");
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales.npz", "application/octet-stream", new byte[]{1});

        assertThrows(RegistrationException.class,
                () -> service.uploadAndRegister(request, file, "upload-request-invalid"));

        verify(uploadClient, never()).upload(any(), any(), anyString(),
                any(), any(), any(), any());
    }

    // ---- domain rule: managing datasets ---------------------------------------------

    private static final AuthenticatedUser ADMIN = user(1L, "admin", null, "ADMIN");
    private static final AuthenticatedUser SH_USER = user(7L, "sh-user", 1L, "DATA_OWNER");
    private static final AuthenticatedUser SZ_USER = user(8L, "sz-user", 2L, "DATA_OWNER");
    private static final AuthenticatedUser SH_AUDITOR = user(9L, "sh-auditor", 1L, "AUDITOR");
    private static final AuthenticatedUser NO_DOMAIN_OWNER = user(10L, "nomad", null, "DATA_OWNER");

    @Test
    void domainUserManagesADatasetLocatedInItsDomain() {
        activeDataset(42L);
        located(42L, location(42L, 1L, "上海域（A）"));
        login(SH_USER);

        service.disable(42L, "disable-own-domain");

        verify(mapper).updateDatasetStatus(42L, "DISABLED", null, false);
    }

    @Test
    void domainUserOfAnotherDomainIsDenied() {
        activeDataset(42L);
        located(42L, location(42L, 1L, "上海域（A）"));
        login(SZ_USER);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.disable(42L, "disable-foreign"));

        assertDomainRequired(error, "only administrators or domain users of the dataset's domain can modify this dataset");
        verify(mapper, never()).updateDatasetStatus(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void domainUserMayManageAgainOnceTheDatasetMovesIntoItsDomain() {
        activeDataset(42L);
        located(42L, location(42L, 1L, "上海域（A）"));
        login(SZ_USER);
        assertThrows(RegistrationException.class, () -> service.disable(42L, "before-move"));

        // A copy/move put a replica on a 深圳 node: the dataset is now (also) in SZ.
        located(42L, location(42L, 1L, "上海域（A）"), location(42L, 2L, "深圳域（B）"));
        service.disable(42L, "after-copy");
        // ...and after the source replica is gone it is SZ only; SH has lost the right.
        located(42L, location(42L, 2L, "深圳域（B）"));
        service.disable(42L, "after-move");
        login(SH_USER);
        assertThrows(RegistrationException.class, () -> service.disable(42L, "sh-after-move"));

        verify(mapper, times(2)).updateDatasetStatus(42L, "DISABLED", null, false);
    }

    @Test
    void adminManagesEveryDatasetEvenOneLocatedNowhere() {
        activeDataset(42L);
        located(42L);
        login(ADMIN);

        service.disable(42L, "admin-disable");

        verify(mapper).updateDatasetStatus(42L, "DISABLED", null, false);
    }

    @Test
    void everyMutationEntryPointRequiresTheDatasetDomain() {
        activeDataset(42L);
        located(42L, location(42L, 1L, "上海域（A）"));
        UpdateDatasetRequest update = new UpdateDatasetRequest();
        update.setRowVersion(0);
        Map<String, Executable> mutations = new LinkedHashMap<>();
        mutations.put("update", () -> service.update(42L, update, "r"));
        mutations.put("verify", () -> service.verify(42L, "r"));
        mutations.put("activate", () -> service.activate(42L, "r"));
        mutations.put("disable", () -> service.disable(42L, "r"));
        mutations.put("addReplica", () -> service.addReplica(42L, 9L, "r"));
        mutations.put("bindRuntimeImage", () -> service.bindRuntimeImage(42L, 5L, "r"));
        mutations.put("unregister", () -> service.unregister(42L, "r"));

        for (AuthenticatedUser denied : Arrays.asList(SZ_USER, SH_AUDITOR, NO_DOMAIN_OWNER)) {
            login(denied);
            for (Map.Entry<String, Executable> mutation : mutations.entrySet()) {
                RegistrationException error = assertThrows(RegistrationException.class, mutation.getValue(),
                        denied.getUsername() + " " + mutation.getKey());
                assertEquals(DatasetRegistrationService.DOMAIN_REQUIRED_CODE, error.getErrorCode(),
                        denied.getUsername() + " " + mutation.getKey());
            }
        }
        verify(mapper, never()).updateDataset(any());
        verify(mapper, never()).updateDatasetStatus(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(mapper, never()).findCandidateById(any());
        verify(mapper, never()).bindRuntimeImage(any(), any());
        verify(mapper, never()).softDeleteDataset(any());
    }

    @Test
    void reverifyWorkerSystemPrincipalIsAnAdministrator() {
        activeDataset(42L);
        located(42L);
        login(new AuthenticatedUser(null, "system", "Replica Reverify Worker",
                Collections.singleton("ADMIN"), null, null, null));

        service.disable(42L, "system");

        verify(mapper).updateDatasetStatus(42L, "DISABLED", null, false);
    }

    // ---- domain rule: registering and uploading ---------------------------------------

    @Test
    void domainUserRegistersACandidateOnANodeOfItsDomainWithoutAHolder() {
        RegisterDatasetRequest request = registerRequest();
        registerableCandidate("sh");
        login(SH_USER);

        RegisteredDatasetView view = service.register(request, "register-own-node");

        assertEquals(Long.valueOf(44L), view.getDatasetId());
        ArgumentCaptor<RegisteredDataset> inserted = ArgumentCaptor.forClass(RegisteredDataset.class);
        verify(mapper).insertDataset(inserted.capture());
        assertEquals("DRAFT", inserted.getValue().getStatus());
        assertEquals(Collections.singletonList(1L), view.getDomainIds());
        assertEquals(Collections.singletonList("上海域（A）"), view.getDomainNames());
    }

    @Test
    void domainUserCannotRegisterACandidateOnAnotherDomainsNode() {
        RegisterDatasetRequest request = registerRequest();
        registerableCandidate("sh");

        for (AuthenticatedUser denied : Arrays.asList(SZ_USER, NO_DOMAIN_OWNER, SH_AUDITOR)) {
            login(denied);
            RegistrationException error = assertThrows(RegistrationException.class,
                    () -> service.register(request, "register-foreign-node"), denied.getUsername());
            assertDomainRequired(error,
                    "only administrators or domain users of the node's domain can register datasets on this node");
        }
        verify(uploadClient, never()).verify(any(), anyString(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).insertDataset(any());
    }

    @Test
    void registrationOntoANodeWithoutAnEnabledDomainIsAdminOnly() {
        RegisterDatasetRequest request = registerRequest();
        registerableCandidate("bj");          // 北京域 exists but is disabled
        login(user(11L, "bj-user", 3L, "DATA_OWNER"));
        assertThrows(RegistrationException.class, () -> service.register(request, "disabled-domain"));

        registerableCandidate(null);         // node has no site at all
        login(SH_USER);
        assertThrows(RegistrationException.class, () -> service.register(request, "no-site"));

        login(ADMIN);
        service.register(request, "admin-anywhere");
        verify(mapper).insertDataset(any(RegisteredDataset.class));
    }

    @Test
    void uploadOntoAnotherDomainsNodeIsDeniedBeforeAnyByteIsSent() {
        UploadDatasetRequest request = uploadRequest();
        NodeManagement storage = storageNode("sz");
        when(nodeMapper.getNodeById(1)).thenReturn(storage);
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales.npz", "application/octet-stream", new byte[]{1, 2, 3});
        login(SH_USER);

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> service.uploadAndRegister(request, file, "upload-foreign"));

        assertEquals(DatasetRegistrationService.DOMAIN_REQUIRED_CODE, error.getErrorCode());
        verify(uploadClient, never()).upload(any(), any(), anyString(), any(), any(), any(), any());
        verify(nodeAvailabilityService, never()).evaluate(any());
    }

    @Test
    void uploadOntoANodeOfTheUsersDomainRegistersWithoutAHolder() {
        UploadDatasetRequest request = uploadRequest();
        NodeManagement storage = storageNode("sz");
        prepareUpload(storage);
        located(44L, location(44L, 2L, "深圳域（B）"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "sales.npz", "application/octet-stream", new byte[]{1, 2, 3});
        login(SZ_USER);

        RegisteredDatasetView view = service.uploadAndRegister(request, file, "upload-own");

        assertEquals(Long.valueOf(44L), view.getDatasetId());
        assertEquals(Collections.singletonList(2L), view.getDomainIds());
        verify(uploadClient).upload(eq(storage), eq(file), anyString(), eq(null), eq("1.0"),
                eq("upload-own"), eq(null));
        verify(mapper).insertDataset(any(RegisteredDataset.class));
    }

    // ---- RegisteredDatasetView location domains --------------------------------------

    @Test
    void datasetListCarriesLocationDomainsFromOneBatchedLookup() {
        RegisteredDataset first = RegisteredDataset.builder().datasetId(5L).name("a").status("ACTIVE").build();
        RegisteredDataset second = RegisteredDataset.builder().datasetId(6L).name("b").status("ACTIVE").build();
        RegisteredDataset nowhere = RegisteredDataset.builder().datasetId(7L).name("c").status("ACTIVE").build();
        when(mapper.listDatasets("", null)).thenReturn(Arrays.asList(first, second, nowhere));
        when(mapper.listReplicas(any())).thenReturn(Collections.emptyList());
        when(locations.findLocationDomains(Arrays.asList(5L, 6L, 7L))).thenReturn(Arrays.asList(
                location(5L, 1L, "上海域（A）"), location(5L, 2L, "深圳域（B）"), location(6L, 2L, "深圳域（B）")));

        List<RegisteredDatasetView> views = service.listDatasets("", null);

        verify(locations, times(1)).findLocationDomains(any());
        assertEquals(Arrays.asList(1L, 2L), views.get(0).getDomainIds());
        assertEquals(Arrays.asList("上海域（A）", "深圳域（B）"), views.get(0).getDomainNames());
        assertEquals(Collections.singletonList(2L), views.get(1).getDomainIds());
        assertEquals(Collections.emptyList(), views.get(2).getDomainIds());
        assertEquals(Collections.emptyList(), views.get(2).getDomainNames());
    }

    @Test
    void datasetViewJsonHasDomainsAndNoHolderFields() throws Exception {
        activeDataset(42L);
        located(42L, location(42L, 2L, "深圳域（B）"));

        ObjectMapper wire = new org.example.json.JacksonObjectMapper();
        String json = wire.writeValueAsString(service.getDataset(42L));

        assertTrue(json.contains("\"domainIds\":[2]"), json);
        assertTrue(json.contains("\"domainNames\":[\"深圳域（B）\"]"), json);
        for (String holder : new String[]{"ownerUserId", "ownerUsername", "ownerDisplayName",
                "ownerDomainId", "ownerDomainCode", "ownerDomainName"}) {
            assertTrue(!json.contains(holder), holder + " in " + json);
        }
        located(42L);
        String nowhere = wire.writeValueAsString(service.getDataset(42L));
        assertTrue(nowhere.contains("\"domainIds\":[]"), nowhere);
        assertTrue(nowhere.contains("\"domainNames\":[]"), nowhere);
    }

    private void activeDataset(Long datasetId) {
        when(mapper.findDatasetById(datasetId)).thenReturn(RegisteredDataset.builder()
                .datasetId(datasetId).name("data-" + datasetId).status("ACTIVE").rowVersion(0).build());
        when(mapper.listReplicas(datasetId)).thenReturn(Collections.emptyList());
    }

    private void located(Long datasetId, DatasetDomainLocation... rows) {
        when(locations.findLocationDomains(Collections.singletonList(datasetId))).thenReturn(Arrays.asList(rows));
    }

    private static DatasetDomainLocation location(Long datasetId, Long domainId, String name) {
        return new DatasetDomainLocation(datasetId, domainId, "domain-" + domainId, name);
    }

    private void login(AuthenticatedUser user) {
        CurrentUserService currentUsers = mock(CurrentUserService.class);
        when(currentUsers.currentUser()).thenReturn(user);
        ReflectionTestUtils.setField(service, "currentUsers", currentUsers);
    }

    private static AuthenticatedUser user(Long id, String username, Long domainId, String... roles) {
        return new AuthenticatedUser(id, username, username, new LinkedHashSet<>(Arrays.asList(roles)),
                domainId, domainId == null ? null : "domain-" + domainId, domainId == null ? null : "D" + domainId);
    }

    private static CollaborationDomain domain(Long id, String siteCode, boolean enabled) {
        CollaborationDomain domain = new CollaborationDomain();
        domain.setId(id);
        domain.setCode("domain-" + id);
        domain.setSiteCode(siteCode);
        domain.setEnabled(enabled);
        return domain;
    }

    private static void assertDomainRequired(RegistrationException error, String message) {
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals("DATASET_DOMAIN_REQUIRED", error.getErrorCode());
        assertEquals(message, error.getMessage());
    }

    private RegisterDatasetRequest registerRequest() {
        RegisterDatasetRequest request = new RegisterDatasetRequest();
        request.setCandidateId(9L);
        request.setDatasetCode("sales");
        request.setName("sales");
        request.setVersion("1.0");
        request.setDataType("NPZ");
        return request;
    }

    /** Candidate 9 on node 1 (site {@code siteCode}); registering it creates dataset 44 located in SH. */
    private void registerableCandidate(String siteCode) {
        DatasetDiscoveryCandidate candidate = DatasetDiscoveryCandidate.builder()
                .candidateId(9L).nodeId(1).filePath("/dataset/sales.npz").sizeBytes(3L)
                .availability("AVAILABLE").build();
        when(mapper.findCandidateById(9L)).thenReturn(candidate);
        when(nodeMapper.getNodeById(1)).thenReturn(NodeManagement.builder().nodeId(1).siteCode(siteCode).build());
        doAnswer(invocation -> {
            invocation.<RegisteredDataset>getArgument(0).setDatasetId(44L);
            return 1;
        }).when(mapper).insertDataset(any(RegisteredDataset.class));
        when(mapper.findDatasetById(44L)).thenReturn(RegisteredDataset.builder()
                .datasetId(44L).datasetCode("sales").name("sales").datasetVersion("1.0").status("DRAFT").build());
        when(mapper.listReplicas(44L)).thenReturn(Collections.emptyList());
        located(44L, location(44L, 1L, "上海域（A）"));
    }

    private NodeManagement storageNode(String siteCode) {
        return NodeManagement.builder().nodeId(1).nodeName("storage-1")
                .internalIp("10.0.0.1").type("storage").registrationStatus("ACTIVE")
                .enabled(true).observedStatus("ONLINE").siteCode(siteCode).build();
    }

    private void prepareUpload(NodeManagement storage) {
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
        doAnswer(invocation -> {
            invocation.<RegisteredDataset>getArgument(0).setDatasetId(44L);
            return 1;
        }).when(mapper).insertDataset(any(RegisteredDataset.class));
        when(mapper.findDatasetById(44L)).thenReturn(RegisteredDataset.builder().datasetId(44L)
                .datasetCode("sales").datasetVersion("1.0").name("Sales")
                .dataType("NPZ").status("DRAFT").rowVersion(0).build());
        when(mapper.listReplicas(44L)).thenReturn(Collections.emptyList());
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
