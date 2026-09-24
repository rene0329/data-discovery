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
                .withNewStatus().addToAllocatable("cpu", new Quantity("4"))
                .addToAllocatable("memory", new Quantity("8Gi")).endStatus().build());
        MixedOperation<Pod, PodList, PodResource> pods = mock(MixedOperation.class, RETURNS_SELF);
        when(client.pods()).thenReturn(pods);
        doReturn(new PodListBuilder().withItems(
                pod("Running", "125m", "256Mi"),
                pod("Pending", "2", "1Gi"),
                pod("Succeeded", "3", "3Gi")).build()).when(pods).list();

        NodeResourceLedger ledger = new NodeResourceService(client)
                .snapshot(Collections.singletonList(node("cluster-sz-1")));

        verify(pods).withField("spec.nodeName", "cluster-sz-1");
        assertEquals("cluster-sz-1 资源不足（需要 2 CPU / 1 GiB，空闲 1.88 CPU / 6.75 GiB）",
                ledger.shortage("cluster-sz-1", JobResourceDemand.of(2.0, 1.0)));
        assertTrue(ledger.fits("cluster-sz-1", JobResourceDemand.of(1.875, 6.75)));
        assertFalse(ledger.fits("cluster-sz-1", JobResourceDemand.of(1.9, 1.0)));
    }

    @Test
    void unreadableClusterMeansNoLedger() {
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.nodes()).thenThrow(new KubernetesClientException("pods is forbidden"));

        assertNull(new NodeResourceService(client).snapshot(Collections.singletonList(node("cluster-sz-1"))));
    }

    @Test
    void ledgerReservesAcceptedJobsAndLetsUnknownNodesFit() {
        NodeResourceLedger ledger = new NodeResourceLedger();
        ledger.put("cluster-sz-1", 3.875, 7.18);
        JobResourceDemand demand = JobResourceDemand.of(2.0, null);

        assertTrue(ledger.fits("cluster-sz-1", demand));
        ledger.reserve("cluster-sz-1", demand);
        assertFalse(ledger.fits("cluster-sz-1", demand));
        assertTrue(ledger.fits("master-215", demand));
        assertEquals(JobResourceDemand.DEFAULT_MEMORY_GI, demand.getMemoryGi());
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
