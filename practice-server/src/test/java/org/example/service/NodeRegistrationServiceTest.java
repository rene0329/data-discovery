package org.example.service;

import org.example.entity.NodeManagement;
import org.example.exception.RegistrationException;
import org.example.mapper.EdgeManagementMapper;
import org.example.mapper.NodeManagementMapper;
import org.example.mapper.NodeRegistrationMapper;
import org.example.mapper.RegistrationAuditMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeRegistrationServiceTest {

    @Test
    void computeNodeDoesNotRequireDiscoveryAgent() {
        assertFalse(NodeRegistrationService.requiresDiscoveryAgent("compute"));
        assertFalse(NodeRegistrationService.requiresDiscoveryAgent(" COMPUTE "));
    }

    @Test
    void dataBearingAndUnknownRolesRequireDiscoveryAgent() {
        assertTrue(NodeRegistrationService.requiresDiscoveryAgent("storage"));
        assertTrue(NodeRegistrationService.requiresDiscoveryAgent("compute-storage"));
        assertTrue(NodeRegistrationService.requiresDiscoveryAgent("worker"));
        assertTrue(NodeRegistrationService.requiresDiscoveryAgent(null));
    }

    @Test
    void unregisterRequiresNodeToBeDisabledFirst() {
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        NodeRegistrationService service = serviceWith(nodes, mock(NodeRegistrationMapper.class),
                mock(EdgeManagementMapper.class), mock(RegistrationAuditMapper.class));
        when(nodes.getNodeById(4)).thenReturn(NodeManagement.builder().nodeId(4)
                .nodeName("alihz").registrationStatus("ACTIVE").enabled(true).build());

        assertThrows(RegistrationException.class, () -> service.unregister(4, "request-1"));
        verify(nodes, never()).softDeleteRegisteredNode(4);
    }

    @Test
    void unregisterDeactivatesLinksAndReleasesDiscoveryCandidate() {
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        NodeRegistrationMapper registration = mock(NodeRegistrationMapper.class);
        EdgeManagementMapper edges = mock(EdgeManagementMapper.class);
        RegistrationAuditMapper audits = mock(RegistrationAuditMapper.class);
        NodeRegistrationService service = serviceWith(nodes, registration, edges, audits);
        when(nodes.getNodeById(4)).thenReturn(NodeManagement.builder().nodeId(4)
                .nodeName("alihz").cluster("cluster-a").k8sUid("uid-4")
                .registrationStatus("DISABLED").enabled(false).build());

        service.unregister(4, "request-2");

        verify(edges).deactivateByNodeId(4);
        verify(nodes).softDeleteRegisteredNode(4);
        verify(registration).clearCandidateRegistration("cluster-a", "uid-4");
    }

    @Test
    void enablingNodeDoesNotCreateFullMeshLinks() {
        NodeManagementMapper nodes = mock(NodeManagementMapper.class);
        EdgeManagementMapper edges = mock(EdgeManagementMapper.class);
        NodeRegistrationService service = serviceWith(nodes, mock(NodeRegistrationMapper.class),
                edges, mock(RegistrationAuditMapper.class));
        when(nodes.getNodeById(4)).thenReturn(NodeManagement.builder().nodeId(4)
                .nodeName("alihz").registrationStatus("DISABLED").enabled(false)
                .verifiedAt(java.time.LocalDateTime.now()).build());
        org.mockito.Mockito.doReturn(null).when(service).getNode(4);
        service.enable(4, "enable-fixed-topology");
        verify(nodes).updateRegistrationState(4, "ACTIVE", true, false);
        org.mockito.Mockito.verifyNoInteractions(edges);
    }

    private NodeRegistrationService serviceWith(NodeManagementMapper nodes,
                                                NodeRegistrationMapper registration,
                                                EdgeManagementMapper edges,
                                                RegistrationAuditMapper audits) {
        NodeRegistrationService service = (NodeRegistrationService) org.mockito.Mockito.mock(
                NodeRegistrationService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "nodeMapper", nodes);
        ReflectionTestUtils.setField(service, "registrationMapper", registration);
        ReflectionTestUtils.setField(service, "edgeMapper", edges);
        ReflectionTestUtils.setField(service, "auditMapper", audits);
        return service;
    }
}
