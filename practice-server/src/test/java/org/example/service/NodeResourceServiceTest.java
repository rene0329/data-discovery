package org.example.service;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.example.entity.NodeManagement;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NodeResourceServiceTest {
    private static final double GIB = 1024d * 1024 * 1024;

    @Test
    void podRequestIsTheLargerOfItsContainersAndItsLargestInitContainer() {
        PodSpec spec = new PodSpecBuilder()
                .withContainers(container("500m", "1Gi"), container("1", "512Mi"))
                .withInitContainers(container("2", "256Mi"), container(null, null))
                .build();

        double[] requested = NodeResourceService.requests(spec);

        assertEquals(2.0, requested[0], 1e-9);
        assertEquals(1.5, requested[1], 1e-9);
        assertArrayEquals(new double[]{0, 0}, NodeResourceService.requests(new PodSpec()), 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    void freeResourcesAreAllocatableMinusRequestsOfUnfinishedPods() {
        KubernetesClient client = mock(KubernetesClient.class);
        NonNamespaceOperation<Node, NodeList, Resource<Node>> nodes = mock(NonNamespaceOperation.class);
        Resource<Node> node = mock(Resource.class);
        when(client.nodes()).thenReturn(nodes);
        when(nodes.withName("cluster-sz-1")).thenReturn(node);
        when(node.get()).thenReturn(new NodeBuilder()
                .withNewStatus().addNewCondition().withType("Ready").withStatus("True").endCondition()
                .addToAllocatable("cpu", new Quantity("4"))
                .addToAllocatable("memory", new Quantity("8Gi"))
                .addToAllocatable("ephemeral-storage", new Quantity("100Gi")).endStatus().build());
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class, RETURNS_SELF);
        when(client.pods()).thenReturn(pods);
        doReturn(new PodListBuilder().withItems(
                new PodBuilder(pod("Running", "125m", "256Mi")).withNewMetadata()
                        .withNamespace("default").addToLabels("job-name", "t4-running").endMetadata().build(),
                pod("Pending", "2", "1Gi"),
                pod("Succeeded", "3", "3Gi")).build()).when(pods).list();

        NodeResourceLedger ledger = new NodeResourceService(client)
                .snapshot(Collections.singletonList(node("cluster-sz-1")));

        verify(pods).withField("spec.nodeName", "cluster-sz-1");
        assertTrue(ledger.shortage("cluster-sz-1", JobResourceDemand.of(2.0, 1.0))
                .contains("空闲 1.88 CPU / 6.75 GiB 内存"));
        assertTrue(ledger.fits("cluster-sz-1", JobResourceDemand.of(1.875, 6.75)));
        assertFalse(ledger.fits("cluster-sz-1", JobResourceDemand.of(1.9, 1.0)));
        assertTrue(ledger.accountsForJob("t4-running"));
        assertFalse(ledger.accountsForJob("t4-not-in-snapshot"));
    }

    @Test
    void unreadableClusterMeansUnknownCapacityAndCannotFit() {
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.nodes()).thenThrow(new KubernetesClientException("pods is forbidden"));

        NodeResourceLedger ledger = new NodeResourceService(client).snapshot(Collections.singletonList(node("cluster-sz-1")));
        assertNotNull(ledger);
        assertFalse(ledger.fits("cluster-sz-1", JobResourceDemand.of(null, null)));
    }

    @Test
    void ledgerReservesAcceptedJobsAndRejectsUnknownNodes() {
        NodeResourceLedger ledger = new NodeResourceLedger();
        ledger.put("cluster-sz-1", 3.875, 7.18);
        JobResourceDemand demand = JobResourceDemand.of(2.0, null);

        assertTrue(ledger.fits("cluster-sz-1", demand));
        ledger.reserve("cluster-sz-1", demand);
        assertFalse(ledger.fits("cluster-sz-1", demand));
        assertFalse(ledger.fits("master-215", demand));
        assertEquals(JobResourceDemand.DEFAULT_MEMORY_GI, demand.getMemoryGi());
    }

    @Test
    void gpuAndStorageAreCountedAndReservedAcrossJobs() {
        PodSpec spec = new PodSpecBuilder().withContainers(new ContainerBuilder().withName("gpu")
                .withNewResources().addToRequests("cpu", new Quantity("1"))
                .addToRequests("memory", new Quantity("2Gi"))
                .addToRequests("nvidia.com/gpu", new Quantity("1"))
                .addToRequests("ephemeral-storage", new Quantity("10Gi")).endResources().build()).build();
        assertArrayEquals(new double[]{1, 2, 1, 10}, NodeResourceService.resourceRequests(spec), 1e-9);
        NodeResourceLedger ledger = new NodeResourceLedger();
        ledger.put("node", 4, 8, 1, 30);
        JobResourceDemand demand = JobResourceDemand.of(1.0, 2.0, 1.0, 10.0);
        assertTrue(ledger.fits("node", demand));
        ledger.reserve("node", demand);
        assertFalse(ledger.fits("node", demand));
        assertEquals(0.0, ledger.availableGpu("node"));
        assertEquals(20.0, ledger.availableEphemeralStorageGi("node"));
    }

    @Test
    void sameNodeNameInDifferentClustersHasSeparateCapacityAndReservations() {
        org.example.factory.K8sJobFactory factory = mock(org.example.factory.K8sJobFactory.class);
        KubernetesClient first = cluster("4", "8Gi");
        KubernetesClient second = cluster("1", "2Gi");
        java.util.Map<String, KubernetesClient> clients = new java.util.HashMap<>();
        clients.put("a", first); clients.put("b", second);
        when(factory.getClusterClients()).thenReturn(clients);
        NodeManagement a = NodeManagement.builder().cluster("a").nodeName("worker").build();
        NodeManagement b = NodeManagement.builder().cluster("b").nodeName("worker").build();
        NodeResourceLedger ledger = new NodeResourceService(factory).snapshot(java.util.Arrays.asList(a, b));
        JobResourceDemand demand = JobResourceDemand.of(2.0, 1.0);
        assertTrue(ledger.fits(a, demand));
        assertFalse(ledger.fits(b, demand));
        ledger.reserve(a, demand);
        assertEquals(2.0, ledger.availableCpu("a/worker"));
        assertEquals(1.0, ledger.availableCpu("b/worker"));
    }

    @Test
    void cordonedNotReadyAndUntoleratedNodesCannotBeAdmitted() {
        Node ready = new NodeBuilder().withNewStatus().addNewCondition().withType("Ready")
                .withStatus("True").endCondition().endStatus().build();
        assertTrue(NodeResourceService.eligible(ready));
        assertFalse(NodeResourceService.eligible(new NodeBuilder(ready).withNewSpec().withUnschedulable(true).endSpec().build()));
        assertFalse(NodeResourceService.eligible(new NodeBuilder(ready).withNewSpec().addNewTaint()
                .withKey("maintenance").withEffect("NoSchedule").endTaint().endSpec().build()));
        assertFalse(NodeResourceService.eligible(new NodeBuilder().withNewStatus().addNewCondition()
                .withType("Ready").withStatus("Unknown").endCondition().endStatus().build()));
        NodeResourceLedger ledger = new NodeResourceLedger();
        ledger.put("node", 8, 32, 0, 100);
        ledger.putCapacity("node", 8, 32, 0, 100);
        ledger.putPodSlots("node", 1, 1);
        JobResourceDemand request = JobResourceDemand.of(1.0, 1.0);
        assertTrue(ledger.fits("node", request));
        ledger.reserve("node", request);
        assertFalse(ledger.fits("node", request));
    }

    @SuppressWarnings("unchecked")
    private KubernetesClient cluster(String cpu, String memory) {
        KubernetesClient client = mock(KubernetesClient.class);
        NonNamespaceOperation<Node, NodeList, Resource<Node>> nodes = mock(NonNamespaceOperation.class);
        Resource<Node> node = mock(Resource.class);
        when(client.nodes()).thenReturn(nodes);
        when(nodes.withName("worker")).thenReturn(node);
        when(node.get()).thenReturn(new NodeBuilder().withNewStatus().addNewCondition().withType("Ready").withStatus("True").endCondition()
                .addToAllocatable("cpu", new Quantity(cpu)).addToAllocatable("memory", new Quantity(memory))
                .addToAllocatable("ephemeral-storage", new Quantity("30Gi")).endStatus().build());
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class, RETURNS_SELF);
        when(client.pods()).thenReturn(pods);
        doReturn(new PodListBuilder().withItems(Collections.emptyList()).build()).when(pods).list();
        return client;
    }

    private static Pod pod(String phase, String cpu, String memory) {
        return new PodBuilder().withSpec(new PodSpecBuilder().withContainers(container(cpu, memory)).build())
                .withNewStatus().withPhase(phase).endStatus().build();
    }

    private static Container container(String cpu, String memory) {
        ContainerBuilder builder = new ContainerBuilder().withName("c");
        if (cpu == null) return builder.build();
        return builder.withNewResources().addToRequests("cpu", new Quantity(cpu))
                .addToRequests("memory", new Quantity(memory)).endResources().build();
    }

    private static NodeManagement node(String name) {
        return NodeManagement.builder().nodeName(name).build();
    }
}
