package org.example;

import org.example.security.aggregation.worker.SecureAggregationWorkerService;
import org.example.service.FileDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = DataDiscoveryDaemonsetApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.lazy-initialization=false",
                "spring.task.scheduling.enabled=false"
        })
class DataDiscoveryDaemonsetApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @MockBean
    private FileDiscoveryService fileDiscoveryService;

    @Test
    void daemonBeansStartWithoutImportingControlPlaneEndpoints() {
        assertTrue(context.containsBean("nodeDatasetAccessController"));
        assertTrue(context.getBeansOfType(SecureAggregationWorkerService.class).size() == 1);
        assertFalse(context.containsBean("datasetAccessSecurityController"));
        assertFalse(context.containsBean("secureAggregationController"));
        assertFalse(context.containsBean("taskV1Controller"));
    }
}
