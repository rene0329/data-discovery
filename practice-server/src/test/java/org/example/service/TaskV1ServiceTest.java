package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.registration.CreateTaskRequest;
import org.example.dto.registration.TaskCreated;
import org.example.dto.registration.TaskPreflightCheck;
import org.example.dto.registration.TaskPreflightResult;
import org.example.entity.RegisteredDataset;
import org.example.entity.DatasetReplica;
import org.example.entity.NodeManagement;
import org.example.entity.RuntimeImage;
import org.example.entity.TaskManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.DatasetRegistrationMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.example.mapper.RuntimeImageMapper;
import org.example.mapper.TaskManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.security.access.DatasetUsagePolicyService;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TaskV1ServiceTest {
    private DatasetRegistrationMapper datasetMapper;
    private RuntimeImageMapper imageMapper;
    private TaskManagementMapper taskMapper;
    private K8sTaskOrchestratorService orchestrator;
    private TaskV1Service service;
    private DatasetReplicaAvailabilityService replicaAvailabilityService;
    private NodeAvailabilityService nodeAvailabilityService;
    private NodeManagementMapper nodeMapper;
    private DatasetUsagePolicyService usagePolicy;
    private NetworkTopologyService topology;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(DatasetRegistrationMapper.class);
        imageMapper = mock(RuntimeImageMapper.class);
        taskMapper = mock(TaskManagementMapper.class);
        orchestrator = mock(K8sTaskOrchestratorService.class);
        replicaAvailabilityService = mock(DatasetReplicaAvailabilityService.class);
        nodeAvailabilityService = mock(NodeAvailabilityService.class);
        nodeMapper = mock(NodeManagementMapper.class);
        usagePolicy = mock(DatasetUsagePolicyService.class);
        topology = mock(NetworkTopologyService.class);
        when(usagePolicy.checkAndAuditTaskDatasets(any(), any())).thenReturn(Collections.emptyList());
        service = new TaskV1Service(datasetMapper, imageMapper, taskMapper,
                mock(RegistrationAuditMapper.class), orchestrator, new ObjectMapper(), nodeMapper,
                replicaAvailabilityService, nodeAvailabilityService, usagePolicy,
                new InPlacePlacementService(nodeMapper, replicaAvailabilityService, nodeAvailabilityService, topology),
                "compute");
    }

    @Test
    void createRejectsOutOfScopeDatasetsBeforeLockingOrPreflight() {
        RegistrationException denied = new RegistrationException(HttpStatus.FORBIDDEN,
                "DATASET_ACCESS_DENIED", "任务创建失败，用户访问受限");
        org.mockito.Mockito.doThrow(denied).when(usagePolicy)
                .requireTaskDatasetsAccessible(Arrays.asList(11L, 12L), "req-denied");
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("mixed");
        request.setDatasetIds(Arrays.asList(11L, 12L));
        request.setExecutionMode("COMPARISON");

        RegistrationException thrown = assertThrows(RegistrationException.class,
                () -> service.create(request, "req-denied"));

        assertEquals("DATASET_ACCESS_DENIED", thrown.getErrorCode());
        verify(datasetMapper, never()).findDatasetById(any());
        verify(taskMapper, never()).submitData(any(TaskManagement.class));
        verify(orchestrator, never()).executeRegisteredTask(any(), any(), any(), any(), any());
    }

    @Test
    void preflightFailsWholeRequestWhenAnyDatasetIsOutOfScope() {
        when(usagePolicy.checkAndAuditTaskDatasets(eq(Arrays.asList(11L, 12L)), any()))
                .thenReturn(Collections.singletonList(12L));
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("mixed");
        request.setDatasetIds(Arrays.asList(11L, 12L));
        request.setExecutionMode("COMPARISON");

        TaskPreflightResult result = service.preflight(request);

        assertFalse(result.isValid());
        assertEquals(1, result.getChecks().size());
        TaskPreflightCheck check = result.getChecks().get(0);
        assertEquals("12", check.getResourceId());
        assertEquals("DATASET_ACCESS_DENIED", check.getErrorCode());
        assertEquals("任务创建失败，用户访问受限", check.getMessage());
        assertEquals("COMPARISON", result.getExecutionMode());
        verify(datasetMapper, never()).findDatasetById(any());
    }

    @Test
    void createAcceptsOnlyActiveDatasetAndUsableImage() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(11L).name("sales.csv").status("ACTIVE").build();
        RuntimeImage image = RuntimeImage.builder()
                .runtimeImageId(3L).status("READY").enabled(true).resolvedDigest("sha256:abc").build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(dataset);
        DatasetReplica replica = DatasetReplica.builder().replicaId(1L).nodeId(3).availability("AVAILABLE").build();
        when(datasetMapper.listReplicas(11L)).thenReturn(Collections.singletonList(replica));
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(imageMapper.findById(3L)).thenReturn(image);
        NodeManagement compute = NodeManagement.builder().nodeId(3).build();
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Collections.singletonList(compute));
        when(nodeAvailabilityService.isSchedulable(compute)).thenReturn(true);
        when(nodeMapper.getNodeByName("compute")).thenReturn(compute);
        doAnswer(invocation -> {
            invocation.<TaskManagement>getArgument(0).setTaskId(42);
            return null;
        }).when(taskMapper).submitData(any(TaskManagement.class));

        CreateTaskRequest request = request();
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        TaskCreated created;
        try {
            created = service.create(request, "request-3");
            org.mockito.Mockito.verifyNoInteractions(orchestrator);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }

        assertEquals(42, created.getTaskId());
        assertEquals("ACCEPTED", created.getStatus());
        verify(orchestrator).executeRegisteredTask(eq(42), eq(Collections.singletonList(11L)), eq(3L),
                eq(null), eq("IN_PLACE"));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(datasetMapper, taskMapper);
        order.verify(datasetMapper).lockDataset(11L);
        order.verify(datasetMapper).countActiveSchedulingReferences(11L);
        order.verify(taskMapper).submitData(any());
        org.mockito.Mockito.clearInvocations(taskMapper, orchestrator);
        when(datasetMapper.countActiveSchedulingReferences(11L)).thenReturn(1);
        assertThrows(RegistrationException.class, () -> service.create(request, "request-busy"));
        org.mockito.Mockito.verifyNoInteractions(taskMapper, orchestrator);
    }

    @Test
    void createRejectsInactiveDataset() {
        when(datasetMapper.findDatasetById(11L)).thenReturn(
                RegisteredDataset.builder().datasetId(11L).status("DRAFT").build());

        assertThrows(RegistrationException.class, () -> service.create(request(), "request-4"));
    }

    @Test
    void preflightRejectsReplicaOnUnavailableNodeBeforeTaskIsAccepted() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(11L).name("catdog").status("ACTIVE").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(1L).nodeId(3)
                .availability("AVAILABLE").build();
        RuntimeImage image = RuntimeImage.builder().runtimeImageId(3L).name("image")
                .status("READY").enabled(true).resolvedDigest("sha256:abc").build();
        NodeManagement compute = NodeManagement.builder().nodeId(2).build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(dataset);
        when(datasetMapper.listReplicas(11L)).thenReturn(Collections.singletonList(replica));
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("UNREACHABLE", false, "节点未启用"));
        when(imageMapper.findById(3L)).thenReturn(image);
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Collections.singletonList(compute));
        when(nodeAvailabilityService.isSchedulable(compute)).thenReturn(true);
        when(nodeMapper.getNodeByName("compute")).thenReturn(compute);

        RegistrationException exception = assertThrows(RegistrationException.class,
                () -> service.create(request(), "request-disabled-node"));

        assertEquals("DATASET_NO_USABLE_REPLICA", exception.getErrorCode());
    }

    @Test
    void centralizedPreflightAllowsAHealthyReplicaOutsideTheComputePool() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(11L).name("catdog").status("ACTIVE").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(1L).nodeId(7)
                .availability("AVAILABLE").build();
        RuntimeImage image = RuntimeImage.builder().runtimeImageId(3L).name("image")
                .status("READY").enabled(true).resolvedDigest("sha256:abc").build();
        NodeManagement central = NodeManagement.builder().nodeId(3).nodeName("compute").build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(dataset);
        when(datasetMapper.listReplicas(11L)).thenReturn(Collections.singletonList(replica));
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(imageMapper.findById(3L)).thenReturn(image);
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Collections.singletonList(central));
        when(nodeAvailabilityService.isSchedulable(central)).thenReturn(true);
        when(nodeMapper.getNodeByName("compute")).thenReturn(central);
        CreateTaskRequest request = request();
        request.setExecutionMode("CENTRALIZED");

        assertTrue(service.preflight(request).isValid());
        assertEquals("CENTRALIZED", service.preflight(request).getExecutionMode());
    }

    @Test
    void comparisonUsesSemanticDurationsWithoutChangingLegacyT1T2() {
        TaskManagement centralized = TaskManagement.builder().taskId(1).executionMode("CENTRALIZED")
                .datasetIdsJson("[11]").runtimeImageId(3L).resourceOverridesJson(null)
                .dataPreparationMs(2400L).executionEvidenceComplete(true).status("已完成")
                .T1(9.0).T2(8.0).build();
        TaskManagement inPlace = TaskManagement.builder().taskId(2).executionMode("IN_PLACE")
                .datasetIdsJson("[11]").runtimeImageId(3L).resourceOverridesJson(null)
                .dataPreparationMs(1200L).executionEvidenceComplete(true).status("已完成")
                .T1(7.0).T2(6.0).build();
        when(taskMapper.listByAcceptanceRun("judge-1", 1))
                .thenReturn(Arrays.asList(centralized, inPlace));

        org.example.dto.registration.TaskRunComparison result = service.compareRun("judge-1", 1);

        assertTrue(result.isComparable());
        assertEquals(2.0, result.getCentralizedToInPlaceRatio());
        assertEquals(9.0, centralized.getT1());
        assertEquals(6.0, inPlace.getT2());
    }

    @Test
    void comparisonDoesNotProduceARatioForZeroDurationEvidence() {
        TaskManagement centralized = TaskManagement.builder().taskId(1).executionMode("CENTRALIZED")
                .datasetIdsJson("[11]").runtimeImageId(3L).dataPreparationMs(0L)
                .executionEvidenceComplete(true).status("已完成").build();
        TaskManagement inPlace = TaskManagement.builder().taskId(2).executionMode("IN_PLACE")
                .datasetIdsJson("[11]").runtimeImageId(3L).dataPreparationMs(1200L)
                .executionEvidenceComplete(true).status("已完成").build();
        when(taskMapper.listByAcceptanceRun("judge-zero", 1))
                .thenReturn(Arrays.asList(centralized, inPlace));

        org.example.dto.registration.TaskRunComparison result = service.compareRun("judge-zero", 1);

        assertFalse(result.isComparable());
        assertNull(result.getCentralizedToInPlaceRatio());
    }

    @Test
    void comparisonCreateProducesOneTaskThatRunsBothModes() {
        RegisteredDataset first = RegisteredDataset.builder()
                .datasetId(11L).name("sales.csv").status("ACTIVE").build();
        RegisteredDataset second = RegisteredDataset.builder()
                .datasetId(12L).name("orders.csv").status("ACTIVE").build();
        DatasetReplica firstReplica = DatasetReplica.builder().replicaId(1L).nodeId(3).build();
        DatasetReplica secondReplica = DatasetReplica.builder().replicaId(2L).nodeId(3).build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(first);
        when(datasetMapper.findDatasetById(12L)).thenReturn(second);
        when(datasetMapper.listReplicas(11L)).thenReturn(Collections.singletonList(firstReplica));
        when(datasetMapper.listReplicas(12L)).thenReturn(Collections.singletonList(secondReplica));
        when(replicaAvailabilityService.evaluate(any())).thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(imageMapper.findById(3L)).thenReturn(RuntimeImage.builder()
                .runtimeImageId(3L).status("READY").enabled(true).resolvedDigest("sha256:abc").build());
        NodeManagement compute = NodeManagement.builder().nodeId(3).nodeName("compute").build();
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Collections.singletonList(compute));
        when(nodeAvailabilityService.isSchedulable(compute)).thenReturn(true);
        when(nodeMapper.getNodeByName("compute")).thenReturn(compute);
        doAnswer(invocation -> {
            invocation.<TaskManagement>getArgument(0).setTaskId(42);
            return null;
        }).when(taskMapper).submitData(any(TaskManagement.class));
        CreateTaskRequest request = request();
        request.setDatasetIds(Arrays.asList(11L, 12L));
        request.setExecutionMode("comparison");

        TaskCreated created = service.create(request, "request-comparison");

        assertEquals(42, created.getTaskId());
        assertEquals("COMPARISON", created.getExecutionMode());
        ArgumentCaptor<TaskManagement> saved = ArgumentCaptor.forClass(TaskManagement.class);
        verify(taskMapper, times(1)).submitData(saved.capture());
        assertEquals("COMPARISON", saved.getValue().getExecutionMode());
        verify(orchestrator, times(1)).executeRegisteredTask(eq(42), eq(Arrays.asList(11L, 12L)), eq(3L),
                eq(null), eq("COMPARISON"));
        verifyNoMoreInteractions(orchestrator);
    }

    @Test
    void comparisonPreflightReturnsTheUnionOfBothModesTaggedByMode() throws Exception {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(11L).name("catdog").status("ACTIVE").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(1L).nodeId(3).build();
        RuntimeImage image = RuntimeImage.builder().runtimeImageId(3L).name("image")
                .status("READY").enabled(true).resolvedDigest("sha256:abc").build();
        NodeManagement compute = NodeManagement.builder().nodeId(3).nodeName("compute").build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(dataset);
        when(datasetMapper.listReplicas(11L)).thenReturn(Collections.singletonList(replica));
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(imageMapper.findById(3L)).thenReturn(image);
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Collections.singletonList(compute));
        when(nodeAvailabilityService.isSchedulable(compute)).thenReturn(true);
        when(nodeMapper.getNodeByName("compute")).thenReturn(compute);
        CreateTaskRequest request = request();
        request.setExecutionMode("COMPARISON");

        TaskPreflightResult result = service.preflight(request);

        assertTrue(result.isValid());
        assertEquals("COMPARISON", result.getExecutionMode());
        Map<String, String> modeByType = new HashMap<>();
        for (TaskPreflightCheck check : result.getChecks()) modeByType.put(check.getResourceType(), check.getExecutionMode());
        assertEquals(new HashSet<>(Arrays.asList("DATASET", "RUNTIME_IMAGE", "COMPUTE_POOL",
                "CENTRAL_NODE", "IN_PLACE_DATASET")), modeByType.keySet());
        assertNull(modeByType.get("DATASET"));
        assertNull(modeByType.get("RUNTIME_IMAGE"));
        assertNull(modeByType.get("COMPUTE_POOL"));
        assertEquals("CENTRALIZED", modeByType.get("CENTRAL_NODE"));
        assertEquals("IN_PLACE", modeByType.get("IN_PLACE_DATASET"));

        TaskPreflightCheck inPlaceCheck = result.getChecks().stream()
                .filter(check -> "IN_PLACE_DATASET".equals(check.getResourceType())).findFirst().get();
        TaskPreflightCheck poolCheck = result.getChecks().stream()
                .filter(check -> "COMPUTE_POOL".equals(check.getResourceType())).findFirst().get();
        ObjectMapper json = new ObjectMapper();
        assertTrue(json.writeValueAsString(inPlaceCheck).contains("\"executionMode\":\"IN_PLACE\""));
        assertTrue(json.writeValueAsString(poolCheck).contains("\"executionMode\":null"));
    }

    @Test
    void comparisonPreflightPassesWhenTheReplicaSiteHasNoComputeNodeButOneIsReachable() {
        // The replica sits on a storage node whose site has no compute node. Distributed
        // placement falls back to the nearest reachable compute node in another site.
        NodeManagement central = storageReplicaFixture();
        Map<Integer, NetworkTopologyService.NetworkPath> paths = new HashMap<>();
        paths.put(7, new NetworkTopologyService.NetworkPath(Collections.singletonList(7), 0.0, Long.MAX_VALUE));
        paths.put(3, new NetworkTopologyService.NetworkPath(Arrays.asList(7, 3), 25.0, 100L));
        when(topology.pathsFrom(7)).thenReturn(paths);
        CreateTaskRequest request = request();
        request.setExecutionMode("COMPARISON");

        TaskPreflightResult result = service.preflight(request);

        assertTrue(result.isValid());
        TaskPreflightCheck inPlace = result.getChecks().stream()
                .filter(check -> "IN_PLACE_DATASET".equals(check.getResourceType())).findFirst().get();
        assertTrue(inPlace.isAvailable());
        assertEquals("AVAILABLE", inPlace.getStatus());
        assertNull(inPlace.getErrorCode());
        assertEquals("cross-site: cluster-hz-1 -> " + central.getNodeName()
                + " (nearest reachable compute node, 25.0 ms)", inPlace.getMessage());
    }

    @Test
    void comparisonPreflightFailsWhenNoComputeNodeIsReachableFromTheReplica() {
        // Same layout, but the storage node has no usable path to any compute node:
        // CENTRALIZED can still run, IN_PLACE cannot, so the comparison task is rejected.
        storageReplicaFixture();
        CreateTaskRequest request = request();
        request.setExecutionMode("COMPARISON");

        TaskPreflightResult result = service.preflight(request);

        assertFalse(result.isValid());
        List<TaskPreflightCheck> failed = result.getChecks().stream()
                .filter(check -> !check.isAvailable()).collect(Collectors.toList());
        assertEquals(1, failed.size());
        assertEquals("IN_PLACE_DATASET", failed.get(0).getResourceType());
        assertEquals("IN_PLACE", failed.get(0).getExecutionMode());
        assertTrue(failed.get(0).getMessage().contains("no reachable compute node from cluster-hz-1"),
                failed.get(0).getMessage());
        assertTrue(result.getChecks().stream().anyMatch(check -> "CENTRAL_NODE".equals(check.getResourceType())
                && check.isAvailable() && "CENTRALIZED".equals(check.getExecutionMode())));
        RegistrationException rejected = assertThrows(RegistrationException.class,
                () -> service.create(request, "request-comparison-rejected"));
        assertEquals("DATASET_NO_IN_PLACE_COMPUTE_REPLICA", rejected.getErrorCode());
        verify(taskMapper, never()).submitData(any());
    }

    @Test
    void inPlacePreflightPrefersASameSiteComputeNodeWithoutACrossSiteNote() {
        NodeManagement central = storageReplicaFixture();
        NodeManagement hzCompute = NodeManagement.builder().nodeId(8).nodeName("cluster-hz-2")
                .type("compute").siteCode("hz").build();
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Arrays.asList(central, hzCompute));
        when(nodeAvailabilityService.isSchedulable(hzCompute)).thenReturn(true);
        CreateTaskRequest request = request();
        request.setExecutionMode("IN_PLACE");

        TaskPreflightResult result = service.preflight(request);

        assertTrue(result.isValid());
        TaskPreflightCheck inPlace = result.getChecks().stream()
                .filter(check -> "IN_PLACE_DATASET".equals(check.getResourceType())).findFirst().get();
        assertTrue(inPlace.isAvailable());
        assertNull(inPlace.getMessage());
        verify(topology, never()).pathsFrom(any());
    }

    /** Dataset 11 with one usable replica on storage-only cluster-hz-1 (site hz); compute node 3 is in site center. */
    private NodeManagement storageReplicaFixture() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(11L).name("catdog").status("ACTIVE").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(1L).nodeId(7).build();
        RuntimeImage image = RuntimeImage.builder().runtimeImageId(3L).name("image")
                .status("READY").enabled(true).resolvedDigest("sha256:abc").build();
        NodeManagement central = NodeManagement.builder().nodeId(3).nodeName("compute")
                .type("compute-storage").siteCode("center").build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(dataset);
        when(datasetMapper.listReplicas(11L)).thenReturn(Collections.singletonList(replica));
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(nodeMapper.getNodeById(7)).thenReturn(NodeManagement.builder().nodeId(7)
                .nodeName("cluster-hz-1").type("storage").siteCode("hz").build());
        when(imageMapper.findById(3L)).thenReturn(image);
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Collections.singletonList(central));
        when(nodeAvailabilityService.isSchedulable(central)).thenReturn(true);
        when(nodeMapper.getNodeByName("compute")).thenReturn(central);
        return central;
    }

    @Test
    void singleModePreflightKeepsOnlyItsOwnModeChecks() {
        RegisteredDataset dataset = RegisteredDataset.builder()
                .datasetId(11L).name("catdog").status("ACTIVE").build();
        DatasetReplica replica = DatasetReplica.builder().replicaId(1L).nodeId(3).build();
        NodeManagement compute = NodeManagement.builder().nodeId(3).nodeName("compute").build();
        when(datasetMapper.findDatasetById(11L)).thenReturn(dataset);
        when(datasetMapper.listReplicas(11L)).thenReturn(Collections.singletonList(replica));
        when(replicaAvailabilityService.evaluate(replica))
                .thenReturn(new ReplicaAvailability("USABLE", true, null));
        when(imageMapper.findById(3L)).thenReturn(RuntimeImage.builder().runtimeImageId(3L)
                .status("READY").enabled(true).resolvedDigest("sha256:abc").build());
        when(nodeMapper.getComputeCapableNodes()).thenReturn(Collections.singletonList(compute));
        when(nodeAvailabilityService.isSchedulable(compute)).thenReturn(true);
        when(nodeMapper.getNodeByName("compute")).thenReturn(compute);
        CreateTaskRequest request = request();

        request.setExecutionMode("CENTRALIZED");
        assertEquals(Collections.singleton("CENTRALIZED"), service.preflight(request).getChecks().stream()
                .map(TaskPreflightCheck::getExecutionMode).filter(Objects::nonNull).collect(Collectors.toSet()));
        request.setExecutionMode(null);
        TaskPreflightResult defaulted = service.preflight(request);
        assertEquals("IN_PLACE", defaulted.getExecutionMode());
        assertEquals(Collections.singleton("IN_PLACE"), defaulted.getChecks().stream()
                .map(TaskPreflightCheck::getExecutionMode).filter(Objects::nonNull).collect(Collectors.toSet()));
        request.setExecutionMode("BOTH");
        assertThrows(RegistrationException.class, () -> service.preflight(request));
    }

    private CreateTaskRequest request() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskName("registered task");
        request.setDatasetIds(Collections.singletonList(11L));
        request.setRuntimeImageId(3L);
        return request;
    }
}
