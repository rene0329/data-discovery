package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
